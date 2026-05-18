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
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.sagebionetworks.repo.model.dbo.search.TextAnalyzerDao;
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
import org.sagebionetworks.util.RetryException;
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
public class OpenSearchManagerImplAutowireTest {

	private static final long POLL_MAX_MS = 30_000L;
	private static final long POLL_INTERVAL_MS = 1_000L;

	private static final int VALIDATE_RETRY_MAX = 10;
	private static final long VALIDATE_RETRY_INITIAL_MS = 1_000L;

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
	public void testValidateAnalyzerSettingsWithCustomTokenFilter() throws Exception {
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
		retryOnAossAnalyzeFlake(() -> {
			assertDoesNotThrow(() -> openSearchManager.validateAnalyzerSettings(settings));
			return null;
		});
	}

	// --- synonym placeholder round-trip (synapse_synonyms expansion at index build) ---

	@Test
	public void testCreateIndexAndSearchWithSynonymPlaceholder() {
		// Custom analyzer with the reserved 'synapse_synonyms' placeholder in the chain.
		// At index-build time OpenSearchManagerImpl substitutes the SynonymSet qnames in
		// place of the placeholder — that's the substitution path under test.
		String analyzerQname = ORG + "-SYNONYM_TEST";
		String synonymQname = ORG + "-SYNONYM_TEST_SET";
		TextAnalyzer analyzer = new TextAnalyzer()
				.setId("9001")
				.setSettings(new TextAnalyzerSettings()
						.setTokenizer(new AnalyzerComponent().setName("standard"))
						.setIndexFilterOrder(Arrays.asList("lowercase", "synapse_synonyms"))
						.setSearchFilterOrder(Arrays.asList("lowercase", "synapse_synonyms")));
		Map<String, TextAnalyzer> analyzers = new HashMap<>();
		analyzers.put(analyzerQname, analyzer);

		SynonymSet synonymSet = new SynonymSet()
				.setOrganizationName(ORG)
				.setName("SYNONYM_TEST_SET")
				.setDefinition("{\"type\":\"synonym_graph\",\"synonyms\":[\"cancer, tumor, neoplasm\"]}");

		List<ColumnModel> columns = Collections.singletonList(
				new ColumnModel().setId("1").setName("diagnosis").setColumnType(ColumnType.STRING));

		// call under test — createIndex must accept the placeholder + synonym graph wiring.
		openSearchManager.createIndex(indexName, columns,
				analyzerQname, analyzerQname,
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

		SearchQueryResults results = waitForSearch(query, columns, analyzers, analyzerQname, 3L);
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

		SearchQuery query = new SearchQuery();
		query.setQueryType(SearchQueryType.MATCH_ALL);
		query.setLimit(10L);
		query.setOffset(0L);
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
	}

	// ---- helpers ----

	private static Map<ColumnType, RoundTripCase> buildEveryColumnTypeCase() {
		Map<ColumnType, RoundTripCase> casesByType = new LinkedHashMap<>();
		casesByType.put(ColumnType.STRING,        new RoundTripCase("alpha",                              "alpha",                                  "alpha"));
		casesByType.put(ColumnType.STRING_LIST,   new RoundTripCase("[\"alpha\",\"beta\"]",               Arrays.asList("alpha", "beta"),           "[\"alpha\",\"beta\"]"));
		casesByType.put(ColumnType.MEDIUMTEXT,    new RoundTripCase("alpha beta gamma",                   "alpha beta gamma",                       "alpha beta gamma"));
		casesByType.put(ColumnType.LARGETEXT,     new RoundTripCase("alpha beta gamma",                   "alpha beta gamma",                       "alpha beta gamma"));
		casesByType.put(ColumnType.LINK,          new RoundTripCase("https://example.org/a",              "https://example.org/a",                  "https://example.org/a"));
		casesByType.put(ColumnType.INTEGER,       new RoundTripCase("123",                                123,                                      "123"));
		casesByType.put(ColumnType.INTEGER_LIST,  new RoundTripCase("[1,2,3]",                            Arrays.asList(1, 2, 3),                   "[1,2,3]"));
		casesByType.put(ColumnType.DATE,          new RoundTripCase("1609459200000",                      1609459200000L,                           "1609459200000"));
		casesByType.put(ColumnType.DATE_LIST,     new RoundTripCase("[1609459200000,1609545600000]",      Arrays.asList(1609459200000L, 1609545600000L),  "[1609459200000,1609545600000]"));
		casesByType.put(ColumnType.FILEHANDLEID,  new RoundTripCase("9876543",                            9876543,                                  "9876543"));
		casesByType.put(ColumnType.SUBMISSIONID,  new RoundTripCase("555",                                555,                                      "555"));
		casesByType.put(ColumnType.EVALUATIONID,  new RoundTripCase("777",                                777,                                      "777"));
		casesByType.put(ColumnType.ENTITYID,      new RoundTripCase("syn123456",                          "syn123456",                              "syn123456"));
		casesByType.put(ColumnType.USERID,        new RoundTripCase("3412396",                            "3412396",                                "3412396"));
		casesByType.put(ColumnType.ENTITYID_LIST, new RoundTripCase("[\"syn1\",\"syn2\"]",                Arrays.asList("syn1", "syn2"),            "[\"syn1\",\"syn2\"]"));
		casesByType.put(ColumnType.USERID_LIST,   new RoundTripCase("[\"100\",\"200\"]",                  Arrays.asList("100", "200"),              "[\"100\",\"200\"]"));
		casesByType.put(ColumnType.DOUBLE,        new RoundTripCase("1.5",                                1.5,                                      "1.5"));
		casesByType.put(ColumnType.BOOLEAN,       new RoundTripCase("true",                               Boolean.TRUE,                             "true"));
		casesByType.put(ColumnType.BOOLEAN_LIST,  new RoundTripCase("[true,false]",                       Arrays.asList(true, false),               "[true,false]"));
		Map<String, Object> jsonExpected = new LinkedHashMap<>();
		jsonExpected.put("a", 1);
		jsonExpected.put("b", "x");
		casesByType.put(ColumnType.JSON,          new RoundTripCase("{\"a\":1,\"b\":\"x\"}",              jsonExpected,                             "{\"a\":1,\"b\":\"x\"}"));
		return casesByType;
	}

	private static final class RoundTripCase {
		final String raw;
		final Object expected;
		final String expectedReturned;
		RoundTripCase(String raw, Object expected, String expectedReturned) {
			this.raw = raw;
			this.expected = expected;
			this.expectedReturned = expectedReturned;
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

	private <T> T retryOnAossAnalyzeFlake(Callable<T> action) throws Exception {
		return TimeUtils.waitForExponentialMaxRetry(VALIDATE_RETRY_MAX, VALIDATE_RETRY_INITIAL_MS, () -> {
			try {
				return action.call();
			} catch (IllegalArgumentException e) {
				if (isAossIndexNotFoundFlake(e)) {
					throw new RetryException(e);
				}
				throw e;
			} catch (AssertionError ae) {
				if (ae.getCause() instanceof IllegalArgumentException
						&& isAossIndexNotFoundFlake((IllegalArgumentException) ae.getCause())) {
					throw new RetryException(ae.getCause());
				}
				throw ae;
			}
		});
	}

	private static boolean isAossIndexNotFoundFlake(IllegalArgumentException e) {
		String message = e.getMessage();
		return message != null && message.contains("index_not_found_exception");
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
