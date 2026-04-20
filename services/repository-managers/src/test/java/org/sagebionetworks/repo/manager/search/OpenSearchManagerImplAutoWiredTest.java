package org.sagebionetworks.repo.manager.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.sagebionetworks.repo.model.search.SearchQuery;
import org.sagebionetworks.repo.model.search.SearchQueryResults;
import org.sagebionetworks.repo.model.search.SearchQueryType;
import org.sagebionetworks.repo.model.search.table.ColumnAnalyzerOverride;
import org.sagebionetworks.repo.model.search.table.SynonymSet;
import org.sagebionetworks.repo.model.search.table.TextAnalyzer;
import org.sagebionetworks.repo.model.search.table.TextAnalyzerSettings;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.util.TimeUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * AutoWire integration test for {@link OpenSearchManagerImpl} that hits real AWS OpenSearch.
 * This class is treated as a DAO-level test — it verifies actual OpenSearch behavior
 * rather than mocked assumptions.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = { "classpath:test-context.xml" })
public class OpenSearchManagerImplAutoWiredTest {

	private static final long POLL_MAX_MS = 30_000L;
	private static final long POLL_INTERVAL_MS = 1_000L;

	@Autowired
	private OpenSearchManager openSearchManager;

	private String indexName;

	@BeforeEach
	public void setUp() {
		assertNotNull(openSearchManager);
		indexName = "test-index-" + UUID.randomUUID().toString().substring(0, 8);
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
		SearchIndexContextProvider context = buildContext(
				column("1", "name", ColumnType.STRING),
				column("2", "age", ColumnType.INTEGER)
		);

		// call under test
		String appliedConfig = openSearchManager.createIndex(indexName, context);

		assertNotNull(appliedConfig);
		assertTrue(appliedConfig.length() > 0);
	}

	@Test
	public void testDeleteIndexWithExistingIndex() {
		openSearchManager.createIndex(indexName, buildContext(column("1", "name", ColumnType.STRING)));

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
		SearchIndexContextProvider context = buildContext(column("1", "name", ColumnType.STRING));

		openSearchManager.createIndex(indexName, context);

		// call under test — resource_already_exists returns null
		String result = openSearchManager.createIndex(indexName, context);

		assertEquals(null, result);
	}

	@Test
	public void testBulkIndexWithEmptyList() {
		// call under test
		long indexed = openSearchManager.bulkIndex(indexName, Collections.emptyList());

		assertEquals(0L, indexed);
	}

	@Test
	public void testCRUDWithSearchQuery() {
		SearchIndexContextProvider context = buildContext(
				column("1", "title", ColumnType.STRING),
				column("2", "count", ColumnType.INTEGER)
		);

		openSearchManager.createIndex(indexName, context);

		List<BulkOperation> operations = new ArrayList<>();
		operations.add(buildBulkOp(indexName, "1", Map.of("_row_id", 1L, "_row_version", 1L, "1", "mitochondria research", "2", "42")));
		operations.add(buildBulkOp(indexName, "2", Map.of("_row_id", 2L, "_row_version", 1L, "1", "genome sequencing study", "2", "99")));
		operations.add(buildBulkOp(indexName, "3", Map.of("_row_id", 3L, "_row_version", 1L, "1", "mitochondria function", "2", "7")));

		// call under test
		long indexed = waitForBulkIndex(operations);

		assertEquals(3L, indexed);

		SearchQuery query = new SearchQuery();
		query.setQueryText("mitochondria");
		query.setQueryType(SearchQueryType.SIMPLE_QUERY_STRING);
		query.setLimit(10L);
		query.setOffset(0L);

		// call under test
		SearchQueryResults results = waitForSearch(query, context, 2);

		assertNotNull(results);
		assertEquals(2L, results.getTotalHits());
		assertNotNull(results.getHits());
		assertEquals(2, results.getHits().size());
	}

	@Test
	public void testSearchWithMatchAllQueryType() {
		SearchIndexContextProvider context = buildContext(column("1", "name", ColumnType.STRING));

		openSearchManager.createIndex(indexName, context);

		List<BulkOperation> operations = new ArrayList<>();
		operations.add(buildBulkOp(indexName, "1", Map.of("_row_id", 1L, "_row_version", 1L, "1", "alpha")));
		operations.add(buildBulkOp(indexName, "2", Map.of("_row_id", 2L, "_row_version", 1L, "1", "beta")));

		waitForBulkIndex(operations);

		SearchQuery query = new SearchQuery();
		query.setQueryType(SearchQueryType.MATCH_ALL);
		query.setLimit(10L);
		query.setOffset(0L);

		// call under test
		SearchQueryResults results = waitForSearch(query, context, 2);

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

		SearchIndexContextProvider context = buildContext(column("1", "name", ColumnType.STRING));

		// call under test
		assertThrows(IllegalStateException.class, () ->
				openSearchManager.search("nonexistent-" + UUID.randomUUID(), query, context));
	}

	// ---- Polling helpers ----

	private long waitForBulkIndex(List<BulkOperation> operations) {
		long[] result = {0L};
		boolean success = TimeUtils.waitForExponential(POLL_MAX_MS, POLL_INTERVAL_MS, null, (v) -> {
			try {
				result[0] = openSearchManager.bulkIndex(indexName, operations);
				return true;
			} catch (RuntimeException e) {
				return false;
			}
		});
		assertTrue(success, "Timed out waiting for bulk index to succeed");
		return result[0];
	}

	private SearchQueryResults waitForSearch(SearchQuery query, SearchIndexContextProvider context,
			long expectedMinHits) {
		SearchQueryResults[] result = {null};
		boolean success = TimeUtils.waitForExponential(POLL_MAX_MS, POLL_INTERVAL_MS, null, (v) -> {
			try {
				result[0] = openSearchManager.search(indexName, query, context);
				return result[0].getTotalHits() != null && result[0].getTotalHits() >= expectedMinHits;
			} catch (IllegalStateException e) {
				return false;
			}
		});
		assertTrue(success, "Timed out waiting for search results (expected at least " + expectedMinHits + " hits)");
		return result[0];
	}

	// ---- Test data helpers ----

	private static SearchIndexContextProvider buildContext(ColumnModel... columns) {
		List<ColumnModel> columnList = Arrays.asList(columns);
		Map<String, TextAnalyzer> analyzers = buildDefaultAnalyzers();
		return new SearchIndexContextProvider() {
			@Override
			public List<ColumnModel> getColumns() {
				return columnList;
			}
			@Override
			public String getDefaultAnalyzer() {
				return null;
			}
			@Override
			public List<ColumnAnalyzerOverride> getColumnAnalyzerOverrides() {
				return Collections.emptyList();
			}
			@Override
			public Map<String, TextAnalyzer> getAnalyzers() {
				return analyzers;
			}
			@Override
			public List<SynonymSet> getSynonymSets() {
				return Collections.emptyList();
			}
		};
	}

	private static ColumnModel column(String id, String name, ColumnType type) {
		ColumnModel cm = new ColumnModel();
		cm.setId(id);
		cm.setName(name);
		cm.setColumnType(type);
		return cm;
	}

	private static Map<String, TextAnalyzer> buildDefaultAnalyzers() {
		Map<String, TextAnalyzer> analyzers = new HashMap<>();
		analyzers.put("org.sagebionetworks-SCIENTIFIC", buildAnalyzer(TextAnalyzerBootstrapper.SCIENTIFIC_ID, "standard"));
		analyzers.put("org.sagebionetworks-KEYWORD", buildAnalyzer(TextAnalyzerBootstrapper.KEYWORD_ID, "keyword"));
		analyzers.put("org.sagebionetworks-STANDARD", buildAnalyzer(TextAnalyzerBootstrapper.STANDARD_ID, "standard"));
		return analyzers;
	}

	private static TextAnalyzer buildAnalyzer(Long id, String tokenizer) {
		TextAnalyzer analyzer = new TextAnalyzer();
		analyzer.setId(id.toString());
		TextAnalyzerSettings settings = new TextAnalyzerSettings();
		settings.setTokenizer(tokenizer);
		settings.setFilterOrder(Collections.singletonList("lowercase"));
		analyzer.setSettings(settings);
		return analyzer;
	}

	private static BulkOperation buildBulkOp(String indexName, String docId, Map<String, Object> doc) {
		return BulkOperation.of(op -> op
				.index(idx -> idx
						.index(indexName)
						.id(docId)
						.document(doc)));
	}
}
