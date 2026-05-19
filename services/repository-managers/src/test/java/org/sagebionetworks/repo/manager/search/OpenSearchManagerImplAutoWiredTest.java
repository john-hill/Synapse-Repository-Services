package org.sagebionetworks.repo.manager.search;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.sagebionetworks.repo.model.dbo.search.TextAnalyzerDao;
import org.sagebionetworks.repo.model.search.FacetRequest;
import org.sagebionetworks.repo.model.search.SearchFieldValue;
import org.sagebionetworks.repo.model.search.SearchQuery;
import org.sagebionetworks.repo.model.search.SearchQueryPart;
import org.sagebionetworks.repo.model.search.SearchQueryResults;
import org.sagebionetworks.repo.model.search.SearchQueryType;
import org.sagebionetworks.repo.model.search.table.AnalyzerComponent;
import org.sagebionetworks.repo.model.search.table.SynonymSet;
import org.sagebionetworks.repo.model.search.table.TextAnalyzer;
import org.sagebionetworks.repo.model.search.table.TextAnalyzerSettings;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.repo.model.table.FacetColumnResult;
import org.sagebionetworks.repo.model.table.FacetColumnResultValueCount;
import org.sagebionetworks.repo.model.table.FacetColumnResultValues;
import org.sagebionetworks.util.TimeUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Autowired test for {@link OpenSearchManagerImpl} against real AOSS — treated as a
 * DAO-level test per the "external-service-backed manager" guidance in CLAUDE.md.
 * The mock-only {@link OpenSearchManagerImplTest} cannot prove the AOSS contract
 * still holds; this class exercises real index lifecycle, real {@code _analyze}
 * round-trips, the synonym placeholder substitution at index-build time, and
 * one round-trip per {@link ColumnType} so the column-type → AOSS field-type
 * mapping is anchored against the live cluster.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = { "classpath:test-context.xml" })
public class OpenSearchManagerImplAutoWiredTest {

	private static final long POLL_MAX_MS = 30_000L;
	private static final long POLL_INTERVAL_MS = 1_000L;

	private static final String ORG = "org.sagebionetworks";
	private static final String SCIENTIFIC_QNAME = ORG + "-SCIENTIFIC";

	@Autowired
	private OpenSearchManager openSearchManager;

	@Autowired
	private TextAnalyzerDao textAnalyzerDao;

	@Autowired
	private TextAnalyzerBootstrap textAnalyzerBootstrap;

	private String indexName;
	private Map<String, TextAnalyzer> defaultAnalyzers;

	@BeforeEach
	public void setUp() {
		assertNotNull(openSearchManager);
		textAnalyzerBootstrap.bootstrapSystemAnalyzers();
		indexName = "test-index-" + UUID.randomUUID().toString().substring(0, 8);
		defaultAnalyzers = loadBootstrappedAnalyzersForTextColumns();
	}

	@AfterEach
	public void tearDown() {
		if (indexName != null) {
			try {
				openSearchManager.deleteIndex(indexName);
			} catch (Exception e) {
				// best-effort cleanup
			}
		}
	}

	// --- index lifecycle ---

	@Test
	public void testCreateIndex() {
		List<ColumnModel> columns = Arrays.asList(
				new ColumnModel().setId("1").setName("title").setColumnType(ColumnType.STRING),
				new ColumnModel().setId("2").setName("count").setColumnType(ColumnType.INTEGER));

		// call under test
		java.util.Optional<String> appliedConfig = openSearchManager.createIndex(indexName, columns,
				SCIENTIFIC_QNAME, SCIENTIFIC_QNAME,
				Collections.emptyList(), defaultAnalyzers, Collections.emptyList());

		assertTrue(appliedConfig.isPresent());
		assertTrue(appliedConfig.get().length() > 0);
	}

	@Test
	public void testDeleteIndex() {
		List<ColumnModel> columns = Collections.singletonList(
				new ColumnModel().setId("1").setName("title").setColumnType(ColumnType.STRING));
		openSearchManager.createIndex(indexName, columns,
				SCIENTIFIC_QNAME, SCIENTIFIC_QNAME,
				Collections.emptyList(), defaultAnalyzers, Collections.emptyList());
		openSearchManager.waitForIndexWritable(indexName);

		// call under test — should succeed and be idempotent on the second call
		openSearchManager.deleteIndex(indexName);
		openSearchManager.deleteIndex(indexName);
	}

	@Test
	public void testDeleteIndexWithNonExistentIndex() {
		// call under test — must not throw
		openSearchManager.deleteIndex("nonexistent-index-" + UUID.randomUUID());
	}

	// --- validate analyzer settings (positive case against live AOSS) ---

	@Test
	public void testValidateAnalyzerSettingsWithCustomTokenFilter() {
		// A custom token filter definition that AOSS must accept via _analyze. The mock
		// validate test only covers the rejection paths (file paths, null tokenizer);
		// this anchors the positive AOSS contract.
		TextAnalyzerSettings settings = new TextAnalyzerSettings()
				.setTokenizer(new AnalyzerComponent().setName("standard"))
				.setTokenFilters(Collections.singletonList(
						new AnalyzerComponent().setName("english_stop")
								.setDefinition("{\"type\":\"stop\",\"stopwords\":\"_english_\"}")))
				.setIndexFilterOrder(Arrays.asList("lowercase", "english_stop"));

		// call under test
		assertDoesNotThrow(() -> openSearchManager.validateAnalyzerSettings(settings));
	}

	// --- synonym placeholder round-trip (synapse_synonyms expansion at index build) ---

	@Test
	public void testCreateIndexAndSearchWithSynonymPlaceholder() {
		// Anchors the SCIENTIFIC + SynonymSet round-trip against live AOSS. The bootstrapped
		// analyzer's chain mixes a word_delimiter_graph filter with the synapse_synonyms
		// placeholder; placing the placeholder anywhere AFTER a graph-emitting filter causes
		// OpenSearch to reject the index at init with "cannot be used to parse synonyms".
		// This test exercises the full path — index creation, bulk indexing, and a query
		// that must traverse the synonym equivalence rule — to lock in both the chain
		// shape and the runtime expansion.
		Map<String, TextAnalyzer> analyzers = new HashMap<>();
		analyzers.put(SCIENTIFIC_QNAME, requireBootstrapped(TextAnalyzerBootstrapper.SCIENTIFIC_ID));

		SynonymSet synonymSet = new SynonymSet()
				.setOrganizationName(ORG)
				.setName("SYNONYM_TEST_SET")
				.setDefinition("{\"type\":\"synonym_graph\",\"synonyms\":[\"cancer, tumor, neoplasm\"]}");

		List<ColumnModel> columns = Collections.singletonList(
				new ColumnModel().setId("1").setName("diagnosis").setColumnType(ColumnType.STRING));

		// call under test — createIndex must accept the SCIENTIFIC analyzer + synonym graph wiring.
		openSearchManager.createIndex(indexName, columns,
				SCIENTIFIC_QNAME, SCIENTIFIC_QNAME,
				Collections.emptyList(), analyzers, Collections.singletonList(synonymSet));
		openSearchManager.waitForIndexWritable(indexName);

		assertEquals(3L, openSearchManager.bulkIndex(indexName, Arrays.asList(
				buildBulkOp(indexName, "1", docOf(1L, "1", "cancer")),
				buildBulkOp(indexName, "2", docOf(2L, "1", "tumor")),
				buildBulkOp(indexName, "3", docOf(3L, "1", "neoplasm")))));

		// A query for any one term must match all three docs via the equivalent synonym rule.
		SearchQuery query = new SearchQuery();
		query.setQueryText("cancer");
		query.setQueryType(SearchQueryType.SIMPLE_QUERY_STRING);
		query.setLimit(10L);
		query.setOffset(0L);

		SearchQueryResults results = waitForSearch(query, columns, analyzers, SCIENTIFIC_QNAME, 3L);
		assertEquals(3L, results.getTotalHits(),
				"Query for 'cancer' must reach all three docs via the equivalent synonym rule wired in via the synapse_synonyms placeholder");
	}

	// --- every-column-type round-trip ---

	@Test
	public void testCRUDWithEveryColumnType() {
		Map<ColumnType, RoundTripCase> casesByType = buildEveryColumnTypeCase();

		assertEquals(EnumSet.allOf(ColumnType.class), casesByType.keySet(),
				"Every Synapse ColumnType must be represented in this round-trip test");

		List<ColumnModel> columns = new ArrayList<>();
		int nextId = 1;
		for (ColumnType type : casesByType.keySet()) {
			columns.add(new ColumnModel().setId(Integer.toString(nextId++))
					.setName("c_" + type.name().toLowerCase())
					.setColumnType(type));
		}

		Map<String, TextAnalyzer> analyzers = loadBootstrappedAnalyzersForAllColumns(columns);

		openSearchManager.createIndex(indexName, columns,
				SCIENTIFIC_QNAME, SCIENTIFIC_QNAME,
				Collections.emptyList(), analyzers, Collections.emptyList());
		openSearchManager.waitForIndexWritable(indexName);

		Map<String, Object> doc = new HashMap<>();
		doc.put("_row_id", 1L);
		doc.put("_row_version", 1L);
		for (ColumnModel column : columns) {
			ColumnType type = column.getColumnType();
			RoundTripCase rtc = casesByType.get(type);
			Object converted = SearchIndexLifecycleManagerImpl.convertForDocument(rtc.raw, type);
			assertEquals(rtc.expected, converted,
					"convertForDocument produced unexpected value for " + type);
			doc.put(column.getId(), converted);
		}

		// call under test — bulk indexing every column type at once must succeed against AOSS
		long indexed = openSearchManager.bulkIndex(indexName, Collections.singletonList(
				BulkOperation.of(op -> op.index(idx -> idx.index(indexName).id("1").document(doc)))));
		assertEquals(1L, indexed);

		// Request facets on every terms-aggregable column in the same call so the
		// numeric (lterms / dterms) and text (sterms) bucket-key paths all run
		// against live AOSS — regression for PLFM-9673 (lterms.keyAsString() is
		// null without an explicit `format`, so INTEGER facets came back with
		// null `value`).
		List<FacetRequest> facetRequests = columns.stream()
				.filter(c -> casesByType.get(c.getColumnType()).expectedFacetValues != null)
				.map(c -> new FacetRequest().setColumnName(c.getName()))
				.collect(Collectors.toList());

		SearchQuery query = new SearchQuery();
		query.setQueryType(SearchQueryType.MATCH_ALL);
		query.setLimit(10L);
		query.setOffset(0L);
		query.setFacetRequests(facetRequests);
		SearchQueryResults results = waitForSearch(query, columns, analyzers, SCIENTIFIC_QNAME, 1L);

		assertEquals(1L, results.getTotalHits());
		assertEquals(1, results.getHits().size());

		Map<String, String> idToName = columns.stream()
				.collect(Collectors.toMap(ColumnModel::getId, ColumnModel::getName));
		Map<String, String> returnedByName = new HashMap<>();
		for (SearchFieldValue fv : results.getHits().get(0).getFields()) {
			returnedByName.put(fv.getName(), fv.getValue());
		}

		for (ColumnModel column : columns) {
			ColumnType type = column.getColumnType();
			String fieldName = idToName.get(column.getId());
			String actual = returnedByName.get(fieldName);
			assertNotNull(actual, "missing returned value for " + type);
			assertEquals(casesByType.get(type).expectedReturned, actual,
					"round-trip mismatch for " + type);
		}

		Map<String, FacetColumnResultValues> facetsByColumn = results.getFacets().stream()
				.collect(Collectors.toMap(FacetColumnResult::getColumnName,
						f -> (FacetColumnResultValues) f));
		for (ColumnModel column : columns) {
			ColumnType type = column.getColumnType();
			Set<String> expectedValues = casesByType.get(type).expectedFacetValues;
			if (expectedValues == null) {
				continue;
			}
			FacetColumnResultValues facet = facetsByColumn.get(column.getName());
			assertNotNull(facet, "missing facet result for " + type);
			Set<String> actualValues = facet.getFacetValues().stream()
					.map(FacetColumnResultValueCount::getValue)
					.collect(Collectors.toSet());
			assertEquals(expectedValues, actualValues,
					"facet bucket values for " + type + " must match (PLFM-9673: null on "
							+ "numeric types prior to fix)");
		}
	}

	// ---- helpers ----

	private static Map<ColumnType, RoundTripCase> buildEveryColumnTypeCase() {
		Map<ColumnType, RoundTripCase> casesByType = new LinkedHashMap<>();
		// JSON fields are mapped to OpenSearch `object` and are not terms-aggregable, so
		// `expectedFacetValues` is null for JSON only and the test skips it for facets.
		casesByType.put(ColumnType.STRING,        new RoundTripCase("alpha",                              "alpha",                                  "alpha",                                  Set.of("alpha")));
		casesByType.put(ColumnType.STRING_LIST,   new RoundTripCase("[\"alpha\",\"beta\"]",               List.of("alpha", "beta"),                 "[\"alpha\",\"beta\"]",                   Set.of("alpha", "beta")));
		casesByType.put(ColumnType.MEDIUMTEXT,    new RoundTripCase("alpha beta gamma",                   "alpha beta gamma",                       "alpha beta gamma",                       Set.of("alpha beta gamma")));
		casesByType.put(ColumnType.LARGETEXT,     new RoundTripCase("alpha beta gamma",                   "alpha beta gamma",                       "alpha beta gamma",                       Set.of("alpha beta gamma")));
		casesByType.put(ColumnType.LINK,          new RoundTripCase("https://example.org/a",              "https://example.org/a",                  "https://example.org/a",                  Set.of("https://example.org/a")));
		casesByType.put(ColumnType.INTEGER,       new RoundTripCase("123",                                123,                                      "123",                                    Set.of("123")));
		casesByType.put(ColumnType.INTEGER_LIST,  new RoundTripCase("[1,2,3]",                            List.of(1, 2, 3),                         "[1,2,3]",                                Set.of("1", "2", "3")));
		casesByType.put(ColumnType.DATE,          new RoundTripCase("1609459200000",                      1609459200000L,                           "1609459200000",                          Set.of("1609459200000")));
		casesByType.put(ColumnType.DATE_LIST,     new RoundTripCase("[1609459200000,1609545600000]",      List.of(1609459200000L, 1609545600000L),  "[1609459200000,1609545600000]",          Set.of("1609459200000", "1609545600000")));
		casesByType.put(ColumnType.FILEHANDLEID,  new RoundTripCase("9876543",                            9876543,                                  "9876543",                                Set.of("9876543")));
		casesByType.put(ColumnType.SUBMISSIONID,  new RoundTripCase("555",                                555,                                      "555",                                    Set.of("555")));
		casesByType.put(ColumnType.EVALUATIONID,  new RoundTripCase("777",                                777,                                      "777",                                    Set.of("777")));
		casesByType.put(ColumnType.ENTITYID,      new RoundTripCase("syn123456",                          "syn123456",                              "syn123456",                              Set.of("syn123456")));
		casesByType.put(ColumnType.USERID,        new RoundTripCase("3412396",                            "3412396",                                "3412396",                                Set.of("3412396")));
		casesByType.put(ColumnType.ENTITYID_LIST, new RoundTripCase("[\"syn1\",\"syn2\"]",                List.of("syn1", "syn2"),                  "[\"syn1\",\"syn2\"]",                    Set.of("syn1", "syn2")));
		casesByType.put(ColumnType.USERID_LIST,   new RoundTripCase("[\"100\",\"200\"]",                  List.of("100", "200"),                    "[\"100\",\"200\"]",                      Set.of("100", "200")));
		casesByType.put(ColumnType.DOUBLE,        new RoundTripCase("1.5",                                1.5,                                      "1.5",                                    Set.of("1.5")));
		casesByType.put(ColumnType.BOOLEAN,       new RoundTripCase("true",                               Boolean.TRUE,                             "true",                                   Set.of("true")));
		casesByType.put(ColumnType.BOOLEAN_LIST,  new RoundTripCase("[true,false]",                       List.of(true, false),                     "[true,false]",                           Set.of("true", "false")));
		casesByType.put(ColumnType.JSON,          new RoundTripCase("{\"a\":1,\"b\":\"x\"}",              Map.of("a", 1, "b", "x"),                 "{\"a\":1,\"b\":\"x\"}",                  null));
		return casesByType;
	}

	private static final class RoundTripCase {
		final String raw;
		final Object expected;
		final String expectedReturned;
		// Expected bucket value strings when this column is requested as a facet.
		// Null for column types that are not terms-aggregable (e.g. JSON object fields).
		final Set<String> expectedFacetValues;
		RoundTripCase(String raw, Object expected, String expectedReturned, Set<String> expectedFacetValues) {
			this.raw = raw;
			this.expected = expected;
			this.expectedReturned = expectedReturned;
			this.expectedFacetValues = expectedFacetValues;
		}
	}

	/**
	 * Bootstrapped TextAnalyzers for the subset of qnames used by single-column tests
	 * — keyed on each {@link ColumnTypeToOpenSearchMapping} default analyzer plus
	 * SCIENTIFIC explicitly. Reading rows from {@link TextAnalyzerDao} keeps the test
	 * from drifting away from {@link TextAnalyzerBootstrapper}.
	 */
	private Map<String, TextAnalyzer> loadBootstrappedAnalyzersForTextColumns() {
		Map<String, TextAnalyzer> analyzers = new HashMap<>();
		analyzers.put(SCIENTIFIC_QNAME, requireBootstrapped(TextAnalyzerBootstrapper.SCIENTIFIC_ID));
		analyzers.put(ORG + "-STANDARD", requireBootstrapped(TextAnalyzerBootstrapper.STANDARD_ID));
		analyzers.put(ORG + "-KEYWORD",  requireBootstrapped(TextAnalyzerBootstrapper.KEYWORD_ID));
		return analyzers;
	}

	private Map<String, TextAnalyzer> loadBootstrappedAnalyzersForAllColumns(List<ColumnModel> columns) {
		Map<String, TextAnalyzer> analyzers = loadBootstrappedAnalyzersForTextColumns();
		for (ColumnModel column : columns) {
			String qname = ColumnTypeToOpenSearchMapping.getDefaultAnalyzerQualifiedName(column.getColumnType());
			Long id = ColumnTypeToOpenSearchMapping.getDefaultAnalyzerId(column.getColumnType());
			analyzers.computeIfAbsent(qname, k -> requireBootstrapped(id));
		}
		return analyzers;
	}

	private TextAnalyzer requireBootstrapped(long id) {
		return textAnalyzerDao.get(id).orElseThrow(() -> new IllegalStateException(
				"Bootstrapped TextAnalyzer not found for id " + id
						+ "; TextAnalyzerBootstrapper should have populated it on startup."));
	}

	private static Map<String, Object> docOf(long rowId, String columnId, String value) {
		Map<String, Object> doc = new HashMap<>();
		doc.put("_row_id", rowId);
		doc.put("_row_version", 1L);
		doc.put(columnId, value);
		return doc;
	}

	private static BulkOperation buildBulkOp(String indexName, String docId, Map<String, Object> doc) {
		return BulkOperation.of(op -> op
				.index(idx -> idx
						.index(indexName)
						.id(docId)
						.document(doc)));
	}

	private SearchQueryResults waitForSearch(SearchQuery query, List<ColumnModel> columns,
			Map<String, TextAnalyzer> analyzers, String defaultSearchAnalyzer,
			long expectedMinHits) {
		SearchQueryResults[] result = {null};
		boolean success = TimeUtils.waitForExponential(POLL_MAX_MS, POLL_INTERVAL_MS, null, (v) -> {
			try {
				result[0] = openSearchManager.search(indexName, query, columns,
						defaultSearchAnalyzer, Collections.emptyList(), analyzers,
						EnumSet.allOf(SearchQueryPart.class));
				return result[0].getTotalHits() != null && result[0].getTotalHits() >= expectedMinHits;
			} catch (IllegalStateException e) {
				// index_not_found — not ready yet
				return false;
			}
		});
		assertTrue(success, "Timed out waiting for search results (expected at least "
				+ expectedMinHits + " hits)");
		return result[0];
	}

	@Test
	public void testValidateAnalyzerSettingsWithNullSettingsThrows() {
		// A trivial unit-style check kept here so this autowired class also serves as
		// the documentation of "validate must reject null inputs without ever calling AOSS".
		assertThrows(IllegalArgumentException.class,
				() -> openSearchManager.validateAnalyzerSettings(null));
	}
}
