package org.sagebionetworks.repo.manager.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
import org.sagebionetworks.repo.model.search.table.TextAnalyzer;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.util.TimeUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * AutoWire integration test for {@link OpenSearchManagerImpl} that hits real AWS OpenSearch.
 * This class is treated as a DAO-level test — it verifies actual OpenSearch behavior
 * rather than mocked assumptions. Document content is verified deeply here so that
 * higher-level tests can trust the DAO and do spot checks only.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = { "classpath:test-context.xml" })
public class OpenSearchManagerImplAutoWiredTest {

	private static final long POLL_MAX_MS = 30_000L;
	private static final long POLL_INTERVAL_MS = 1_000L;

	@Autowired
	private OpenSearchManager openSearchManager;

	@Autowired
	private TextAnalyzerDao textAnalyzerDao;

	@Autowired
	private TextAnalyzerBootstrap textAnalyzerBootstrap;

	private String indexName;
	/**
	 * Per-test resolved-analyzer map. Each value is the post-{@code SearchAnalyzerJson.resolveRefs}
	 * settings tree the manager hands to AOSS at index-build time. The bootstrapped analyzers
	 * contain no {@code $ref}s, so parsing the stored {@code settings} blob is sufficient — no
	 * SynonymSet substitution needed for this test class.
	 */
	private Map<String, JsonNode> defaultAnalyzers;

	@BeforeEach
	public void setUp() {
		assertNotNull(openSearchManager);
		textAnalyzerBootstrap.bootstrapSystemAnalyzers();
		indexName = "test-index-" + UUID.randomUUID().toString().substring(0, 8);
		defaultAnalyzers = buildDefaultAnalyzers();
	}

	@AfterEach
	public void tearDown() {
		if (indexName != null) {
			try {
				openSearchManager.deleteIndex(indexName);
			} catch (Exception e) {
				// Best effort cleanup
			}
		}
	}

	@Test
	public void testCreateIndexWithValidColumns() {
		List<ColumnModel> columns = List.of(
				new ColumnModel().setId("1").setName("name").setColumnType(ColumnType.STRING),
				new ColumnModel().setId("2").setName("age").setColumnType(ColumnType.INTEGER)
		);

		// call under test
		Optional<String> appliedConfig = openSearchManager.createIndex(indexName, columns, null,
				Collections.emptyList(), defaultAnalyzers);

		assertTrue(appliedConfig.isPresent());
		assertTrue(appliedConfig.get().length() > 0);
	}

	@Test
	public void testDeleteIndexWithExistingIndex() {
		List<ColumnModel> columns = List.of(
				new ColumnModel().setId("1").setName("name").setColumnType(ColumnType.STRING));
		openSearchManager.createIndex(indexName, columns, null,
				Collections.emptyList(), defaultAnalyzers);
		openSearchManager.waitForIndexWritable(indexName);

		// call under test
		openSearchManager.deleteIndex(indexName);

		// call under test — deleting again should be a no-op
		openSearchManager.deleteIndex(indexName);
	}

	@Test
	public void testDeleteIndexWithNonExistentIndex() {
		// call under test — should not throw
		openSearchManager.deleteIndex("nonexistent-index-" + UUID.randomUUID());
	}

	@Test
	public void testCreateIndexWithDuplicateName() {
		List<ColumnModel> columns = List.of(
				new ColumnModel().setId("1").setName("name").setColumnType(ColumnType.STRING));
		openSearchManager.createIndex(indexName, columns, null,
				Collections.emptyList(), defaultAnalyzers);
		openSearchManager.waitForIndexWritable(indexName);

		// call under test — resource_already_exists returns empty Optional
		Optional<String> result = openSearchManager.createIndex(indexName, columns, null,
				Collections.emptyList(), defaultAnalyzers);

		assertTrue(result.isEmpty());
	}

	@Test
	public void testBulkIndexWithEmptyList() {
		// call under test
		long indexed = openSearchManager.bulkIndex(indexName, Collections.emptyList());

		assertEquals(0L, indexed);
	}

	@Test
	public void testCRUDWithSearchQueryAndDocumentVerification() {
		List<ColumnModel> columns = List.of(
				new ColumnModel().setId("1").setName("title").setColumnType(ColumnType.STRING),
				new ColumnModel().setId("2").setName("count").setColumnType(ColumnType.INTEGER)
		);
		openSearchManager.createIndex(indexName, columns, null,
				Collections.emptyList(), defaultAnalyzers);
		openSearchManager.waitForIndexWritable(indexName);

		List<BulkOperation> operations = List.of(
				buildBulkOp(indexName, "1", Map.of("_row_id", 1L, "_row_version", 1L, "1", "mitochondria research", "2", "42")),
				buildBulkOp(indexName, "2", Map.of("_row_id", 2L, "_row_version", 1L, "1", "genome sequencing study", "2", "99")),
				buildBulkOp(indexName, "3", Map.of("_row_id", 3L, "_row_version", 1L, "1", "mitochondria function", "2", "7"))
		);

		// call under test — bulk index should succeed on first attempt without retries
		long indexed = openSearchManager.bulkIndex(indexName, operations);

		assertEquals(3L, indexed);

		SearchQuery query = new SearchQuery();
		query.setQueryText("mitochondria");
		query.setQueryType(SearchQueryType.SIMPLE_QUERY_STRING);
		query.setLimit(10L);
		query.setOffset(0L);

		// call under test — poll for search results (AOSS eventual consistency)
		SearchQueryResults results = waitForSearch(query, columns, 2);

		assertNotNull(results);
		assertEquals(2L, results.getTotalHits());
		assertNotNull(results.getHits());
		assertEquals(2, results.getHits().size());

		// Verify actual document content — this is the deep check so higher-level
		// tests can trust the DAO and just do count/spot checks.
		// Note: convertResponse translates column IDs back to names using idToName map.
		results.getHits().forEach(hit -> {
			assertNotNull(hit.getFields(), "Hit should have fields");
			assertTrue(hit.getFields().stream().anyMatch(f -> "title".equals(f.getName())),
					"Hit should have field 'title' (translated from column ID '1')");
			String titleValue = hit.getFields().stream()
					.filter(f -> "title".equals(f.getName()))
					.findFirst().get().getValue();
			assertTrue(titleValue.contains("mitochondria"),
					"Title field should contain 'mitochondria', got: " + titleValue);
		});
	}

	@Test
	public void testSearchWithMatchAllQueryType() {
		List<ColumnModel> columns = List.of(
				new ColumnModel().setId("1").setName("name").setColumnType(ColumnType.STRING));
		openSearchManager.createIndex(indexName, columns, null,
				Collections.emptyList(), defaultAnalyzers);
		openSearchManager.waitForIndexWritable(indexName);

		List<BulkOperation> operations = List.of(
				buildBulkOp(indexName, "1", Map.of("_row_id", 1L, "_row_version", 1L, "1", "alpha")),
				buildBulkOp(indexName, "2", Map.of("_row_id", 2L, "_row_version", 1L, "1", "beta"))
		);
		openSearchManager.bulkIndex(indexName, operations);

		SearchQuery query = new SearchQuery();
		query.setQueryType(SearchQueryType.MATCH_ALL);
		query.setLimit(10L);
		query.setOffset(0L);

		// call under test
		SearchQueryResults results = waitForSearch(query, columns, 2);

		assertNotNull(results);
		assertEquals(2L, results.getTotalHits());
		assertNotNull(results.getHits());
		assertEquals(2, results.getHits().size());
	}

	@Test
	public void testSearchWithNonExistentIndex() {
		SearchQuery query = new SearchQuery();
		query.setQueryText("anything");
		query.setQueryType(SearchQueryType.SIMPLE_QUERY_STRING);
		query.setLimit(10L);
		query.setOffset(0L);

		List<ColumnModel> columns = List.of(
				new ColumnModel().setId("1").setName("name").setColumnType(ColumnType.STRING));

		// call under test
		IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
				openSearchManager.search("nonexistent-" + UUID.randomUUID(), query, columns,
						EnumSet.allOf(SearchQueryPart.class)));

		assertTrue(ex.getMessage().contains("still building"),
				"Exception message should indicate the index is not ready, got: " + ex.getMessage());
	}

	@Test
	public void testValidateAnalyzerSettingsWithInvalidTokenizer() {
		// Bare built-in tokenizer reference that AOSS doesn't recognize.
		JsonNode settings = SearchAnalyzerJson.parse("{\"analyzer\":{\"default\":{\"type\":\"custom\","
				+ "\"tokenizer\":\"nonexistent_tokenizer_xyz\"}}}");

		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> openSearchManager.validateAnalyzerSettings(settings));
		assertTrue(ex.getMessage().contains("Invalid analyzer configuration"),
				"Expected 'Invalid analyzer configuration' in message, got: " + ex.getMessage());
	}

	@Test
	public void testValidateAnalyzerSettingsWithInvalidFilter() {
		// Built-in tokenizer paired with a filter chain that names a nonexistent built-in filter.
		JsonNode settings = SearchAnalyzerJson.parse("{\"analyzer\":{\"default\":{\"type\":\"custom\","
				+ "\"tokenizer\":\"standard\","
				+ "\"filter\":[\"bogus_filter_name_xyz\"]}}}");

		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> openSearchManager.validateAnalyzerSettings(settings));
		assertTrue(ex.getMessage().contains("Invalid analyzer configuration"),
				"Expected 'Invalid analyzer configuration' in message, got: " + ex.getMessage());
	}

	@Test
	public void testValidateAnalyzerSettingsWithInlineFilterRegistry() {
		// Inline filter registry (my_stop) plus a built-in (lowercase). Exercises the typed
		// TokenFilterDefinition deserialize path against live AOSS.
		JsonNode settings = SearchAnalyzerJson.parse("{"
				+ "\"filter\":{\"my_stop\":{\"type\":\"stop\",\"stopwords\":\"_english_\"}},"
				+ "\"analyzer\":{\"default\":{\"type\":\"custom\","
				+ "\"tokenizer\":\"standard\","
				+ "\"filter\":[\"my_stop\",\"lowercase\"]}}}");

		// call under test — must not throw
		openSearchManager.validateAnalyzerSettings(settings);
	}

	@Test
	public void testValidateAnalyzerSettingsWithBootstrappedAnalyzer() {
		// Round-trip every bootstrapped analyzer's settings through the validate probe so a
		// regression on any one of them surfaces here. Each analyzer's stored settings is
		// already a complete OpenSearch settings.analysis tree with no $refs.
		for (Map.Entry<String, JsonNode> entry : defaultAnalyzers.entrySet()) {
			// call under test
			openSearchManager.validateAnalyzerSettings(entry.getValue());
		}
	}

	/**
	 * Round-trips one row through every Synapse {@link ColumnType} simultaneously: each fixture
	 * pairs the raw String value (the form delivered by {@code tableQueryManager.runQueryAsStream})
	 * with the typed Java value the production converter should produce. The test exercises both
	 * the converter and the AOSS contract — bulk index must accept every column type, and the
	 * search response must return the values back. A coverage guard fails the test if a new
	 * ColumnType is added to the enum without a fixture row.
	 */
	@Test
	public void testCRUDWithEveryColumnType() {
		Map<ColumnType, RoundTripCase> casesByType = buildEveryColumnTypeCase();

		assertEquals(EnumSet.allOf(ColumnType.class), casesByType.keySet(),
				"Every Synapse ColumnType must be represented in this round-trip test");

		List<ColumnModel> columns = new ArrayList<>();
		int nextId = 1;
		for (ColumnType type : casesByType.keySet()) {
			String columnId = Integer.toString(nextId++);
			columns.add(new ColumnModel().setId(columnId)
					.setName("c_" + type.name().toLowerCase())
					.setColumnType(type));
		}

		openSearchManager.createIndex(indexName, columns, null,
				Collections.emptyList(), defaultAnalyzers);
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

		// call under test
		long indexed = openSearchManager.bulkIndex(indexName, List.of(
				BulkOperation.of(op -> op.index(idx -> idx.index(indexName).id("1").document(doc)))));

		assertEquals(1L, indexed);

		SearchQuery query = new SearchQuery();
		query.setQueryType(SearchQueryType.MATCH_ALL);
		query.setLimit(10L);
		query.setOffset(0L);
		SearchQueryResults results = waitForSearch(query, columns, 1L);

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

	private static Map<ColumnType, RoundTripCase> buildEveryColumnTypeCase() {
		Map<ColumnType, RoundTripCase> casesByType = new LinkedHashMap<>();
		casesByType.put(ColumnType.STRING,        new RoundTripCase("alpha",                              "alpha",                                  "alpha"));
		casesByType.put(ColumnType.STRING_LIST,   new RoundTripCase("[\"alpha\",\"beta\"]",               List.of("alpha", "beta"),                 "[\"alpha\",\"beta\"]"));
		casesByType.put(ColumnType.MEDIUMTEXT,    new RoundTripCase("alpha beta gamma",                   "alpha beta gamma",                       "alpha beta gamma"));
		casesByType.put(ColumnType.LARGETEXT,     new RoundTripCase("alpha beta gamma",                   "alpha beta gamma",                       "alpha beta gamma"));
		casesByType.put(ColumnType.LINK,          new RoundTripCase("https://example.org/a",              "https://example.org/a",                  "https://example.org/a"));
		casesByType.put(ColumnType.INTEGER,       new RoundTripCase("123",                                123,                                      "123"));
		casesByType.put(ColumnType.INTEGER_LIST,  new RoundTripCase("[1,2,3]",                            List.of(1, 2, 3),                         "[1,2,3]"));
		casesByType.put(ColumnType.DATE,          new RoundTripCase("1609459200000",                      1609459200000L,                           "1609459200000"));
		casesByType.put(ColumnType.DATE_LIST,     new RoundTripCase("[1609459200000,1609545600000]",      List.of(1609459200000L, 1609545600000L),  "[1609459200000,1609545600000]"));
		casesByType.put(ColumnType.FILEHANDLEID,  new RoundTripCase("9876543",                            9876543,                                  "9876543"));
		casesByType.put(ColumnType.SUBMISSIONID,  new RoundTripCase("555",                                555,                                      "555"));
		casesByType.put(ColumnType.EVALUATIONID,  new RoundTripCase("777",                                777,                                      "777"));
		casesByType.put(ColumnType.ENTITYID,      new RoundTripCase("syn123456",                          "syn123456",                              "syn123456"));
		casesByType.put(ColumnType.USERID,        new RoundTripCase("3412396",                            "3412396",                                "3412396"));
		casesByType.put(ColumnType.ENTITYID_LIST, new RoundTripCase("[\"syn1\",\"syn2\"]",                List.of("syn1", "syn2"),                  "[\"syn1\",\"syn2\"]"));
		casesByType.put(ColumnType.USERID_LIST,   new RoundTripCase("[\"100\",\"200\"]",                  List.of("100", "200"),                    "[\"100\",\"200\"]"));
		casesByType.put(ColumnType.DOUBLE,        new RoundTripCase("1.5",                                1.5,                                      "1.5"));
		casesByType.put(ColumnType.BOOLEAN,       new RoundTripCase("true",                               Boolean.TRUE,                             "true"));
		casesByType.put(ColumnType.BOOLEAN_LIST,  new RoundTripCase("[true,false]",                       List.of(true, false),                     "[true,false]"));
		casesByType.put(ColumnType.JSON,          new RoundTripCase("{\"a\":1,\"b\":\"x\"}",              Map.of("a", 1, "b", "x"),                 "{\"a\":1,\"b\":\"x\"}"));
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
	 * Loads a bootstrapped system analyzer from the database by id and parses its stored
	 * settings JSON. Reading the live row keeps these tests from drifting away from the
	 * real configuration emitted by {@link TextAnalyzerBootstrapper}.
	 */
	private JsonNode bootstrappedAnalyzerSettings(long id) {
		TextAnalyzer ta = textAnalyzerDao.get(id).orElseThrow(() -> new IllegalStateException(
				"Bootstrapped TextAnalyzer not found for id " + id
						+ "; TextAnalyzerBootstrapper should have populated it on startup."));
		return SearchAnalyzerJson.parse(ta.getSettings());
	}

	// ---- Polling helpers ----

	/**
	 * Poll until search returns at least {@code expectedMinHits} results.
	 * AOSS is eventually consistent — documents may not be visible immediately after indexing.
	 */
	private SearchQueryResults waitForSearch(SearchQuery query, List<ColumnModel> columns,
			long expectedMinHits) {
		SearchQueryResults[] result = {null};
		boolean success = TimeUtils.waitForExponential(POLL_MAX_MS, POLL_INTERVAL_MS, null, (v) -> {
			try {
				result[0] = openSearchManager.search(indexName, query, columns,
						EnumSet.allOf(SearchQueryPart.class));
				return result[0].getTotalHits() != null && result[0].getTotalHits() >= expectedMinHits;
			} catch (IllegalStateException e) {
				// index_not_found — not ready yet
				return false;
			}
		});
		assertTrue(success, "Timed out waiting for search results (expected at least " + expectedMinHits + " hits)");
		return result[0];
	}

	// ---- Test data helpers ----

	private Map<String, JsonNode> buildDefaultAnalyzers() {
		Map<String, JsonNode> analyzers = new HashMap<>();
		analyzers.put("org.sagebionetworks-SCIENTIFIC", bootstrappedAnalyzerSettings(TextAnalyzerBootstrapper.SCIENTIFIC_ID));
		analyzers.put("org.sagebionetworks-KEYWORD", bootstrappedAnalyzerSettings(TextAnalyzerBootstrapper.KEYWORD_ID));
		analyzers.put("org.sagebionetworks-STANDARD", bootstrappedAnalyzerSettings(TextAnalyzerBootstrapper.STANDARD_ID));
		return analyzers;
	}

	private static BulkOperation buildBulkOp(String indexName, String docId, Map<String, Object> doc) {
		return BulkOperation.of(op -> op
				.index(idx -> idx
						.index(indexName)
						.id(docId)
						.document(doc)));
	}
}
