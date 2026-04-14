package org.sagebionetworks.repo.manager.search;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.ErrorCause;
import org.opensearch.client.opensearch._types.ErrorResponse;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch._types.ShardStatistics;
import org.opensearch.client.opensearch._types.aggregations.Aggregate;
import org.opensearch.client.opensearch._types.aggregations.StringTermsBucket;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem;
import org.opensearch.client.opensearch.core.bulk.OperationType;
import org.opensearch.client.opensearch.core.search.Hit;
import org.opensearch.client.opensearch.core.search.TotalHits;
import org.opensearch.client.opensearch.core.search.TotalHitsRelation;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.opensearch.client.opensearch.indices.CreateIndexResponse;
import org.opensearch.client.opensearch.indices.DeleteIndexRequest;
import org.opensearch.client.opensearch.indices.DeleteIndexResponse;
import org.opensearch.client.opensearch.indices.OpenSearchIndicesClient;
import org.sagebionetworks.repo.model.search.FacetRequest;
import org.sagebionetworks.repo.model.search.FacetSortField;
import org.sagebionetworks.repo.model.search.KeyRange;
import org.sagebionetworks.repo.model.search.KeyValues;
import org.sagebionetworks.repo.model.search.SearchFieldValue;
import org.sagebionetworks.repo.model.search.SearchHit;
import org.sagebionetworks.repo.model.search.SearchQuery;
import org.sagebionetworks.repo.model.search.SearchQueryResults;
import org.sagebionetworks.repo.model.search.SearchQueryType;
import org.sagebionetworks.repo.model.search.SortDirection;
import org.sagebionetworks.repo.model.search.SortField;
import org.sagebionetworks.repo.model.search.table.ColumnAnalyzerOverride;
import org.sagebionetworks.repo.model.search.table.ColumnAnalyzerOverrideEntry;
import org.sagebionetworks.repo.model.search.table.SynonymRule;
import org.sagebionetworks.repo.model.search.table.SynonymRuleType;
import org.sagebionetworks.repo.model.search.table.SynonymSet;
import org.sagebionetworks.repo.model.search.table.TextAnalyzer;
import org.sagebionetworks.repo.model.search.table.TextAnalyzerSettings;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.repo.model.table.FacetType;

@ExtendWith(MockitoExtension.class)
public class OpenSearchManagerImplTest {

	private static final String INDEX_NAME = "test-index";

	@Mock
	private OpenSearchClient openSearchClient;

	@Mock
	private OpenSearchIndicesClient indicesClient;

	private OpenSearchManagerImpl manager;

	@BeforeEach
	void setUp() {
		manager = new OpenSearchManagerImpl(openSearchClient);
	}

	// ---- Shared fixtures ----

	private TextAnalyzer buildTextAnalyzer(String id, String tokenizer) {
		TextAnalyzerSettings settings = new TextAnalyzerSettings();
		settings.setTokenizer(tokenizer);
		TextAnalyzer analyzer = new TextAnalyzer();
		analyzer.setId(id);
		analyzer.setSettings(settings);
		return analyzer;
	}

	private Map<String, TextAnalyzer> buildStandardAnalyzers() {
		Map<String, TextAnalyzer> analyzers = new HashMap<>();
		// SCIENTIFIC_ID = 1
		analyzers.put("org-scientific", buildTextAnalyzer("1", "standard"));
		// STANDARD_ID = 2
		analyzers.put("org-standard", buildTextAnalyzer("2", "standard"));
		// KEYWORD_ID = 4
		analyzers.put("org-keyword", buildTextAnalyzer("4", "keyword"));
		return analyzers;
	}

	private ColumnModel buildColumn(String id, String name, ColumnType type) {
		ColumnModel cm = new ColumnModel();
		cm.setId(id);
		cm.setName(name);
		cm.setColumnType(type);
		return cm;
	}

	private List<ColumnModel> buildTestColumns() {
		return Arrays.asList(
			buildColumn("123", "studyName", ColumnType.STRING),
			buildColumn("456", "disease", ColumnType.STRING),
			buildColumn("789", "age", ColumnType.INTEGER),
			buildColumn("101", "url", ColumnType.LINK),
			buildColumn("102", "entityRef", ColumnType.ENTITYID)
		);
	}

	private OpenSearchException buildOpenSearchException(String type, String reason) {
		return new OpenSearchException(ErrorResponse.of(er -> er
			.error(ErrorCause.of(ec -> ec.type(type).reason(reason)))
			.status(400)));
	}

	@SuppressWarnings("rawtypes")
	private SearchResponse<Map> buildSearchResponse(long totalHits, List<Hit<Map>> hits,
			Map<String, Aggregate> aggregations) {
		return SearchResponse.searchResponseOf(sr -> sr
			.hits(h -> h
				.total(TotalHits.of(th -> th.value(totalHits).relation(TotalHitsRelation.Eq)))
				.hits(hits))
			.took(5)
			.timedOut(false)
			.shards(ShardStatistics.of(sh -> sh.successful(1).failed(0).total(1)))
			.aggregations(aggregations != null ? aggregations : Collections.emptyMap()));
	}

	@SuppressWarnings("rawtypes")
	private Hit<Map> buildHit(String id, double score, Map<String, Object> source) {
		return Hit.of(hit -> hit.id(id).index(INDEX_NAME).score(score).source(source));
	}

	@SuppressWarnings("rawtypes")
	private Hit<Map> buildHitWithHighlights(String id, double score, Map<String, Object> source,
			Map<String, List<String>> highlights) {
		return Hit.of(hit -> hit.id(id).index(INDEX_NAME).score(score).source(source).highlight(highlights));
	}

	// ---- CreateIndex tests ----

	@Nested
	class CreateIndexTests {

		@Test
		public void testCreateIndexWithAcknowledged() throws IOException {
			when(openSearchClient.indices()).thenReturn(indicesClient);
			CreateIndexResponse response = new CreateIndexResponse.Builder()
				.index(INDEX_NAME).acknowledged(true).shardsAcknowledged(true).build();
			when(indicesClient.create(any(CreateIndexRequest.class))).thenReturn(response);

			// call under test
			String result = manager.createIndex(INDEX_NAME, buildTestColumns(), "org-scientific",
				Collections.emptyList(), Collections.emptyList(), buildStandardAnalyzers());

			assertNotNull(result);
			verify(indicesClient).create(any(CreateIndexRequest.class));
		}

		@Test
		public void testCreateIndexWithNotAcknowledged() throws IOException {
			when(openSearchClient.indices()).thenReturn(indicesClient);
			CreateIndexResponse response = new CreateIndexResponse.Builder()
				.index(INDEX_NAME).acknowledged(false).shardsAcknowledged(false).build();
			when(indicesClient.create(any(CreateIndexRequest.class))).thenReturn(response);

			// call under test
			String result = manager.createIndex(INDEX_NAME, buildTestColumns(), "org-scientific",
				Collections.emptyList(), Collections.emptyList(), buildStandardAnalyzers());

			assertNotNull(result);
		}

		@Test
		public void testCreateIndexWithResourceAlreadyExists() throws IOException {
			when(openSearchClient.indices()).thenReturn(indicesClient);
			when(indicesClient.create(any(CreateIndexRequest.class)))
				.thenThrow(buildOpenSearchException("resource_already_exists_exception", "index already exists"));

			// call under test
			String result = manager.createIndex(INDEX_NAME, buildTestColumns(), "org-scientific",
				Collections.emptyList(), Collections.emptyList(), buildStandardAnalyzers());

			assertNull(result);
			verifyNoMoreInteractions(indicesClient);
		}

		@Test
		public void testCreateIndexWithOpenSearchException() throws IOException {
			when(openSearchClient.indices()).thenReturn(indicesClient);
			when(indicesClient.create(any(CreateIndexRequest.class)))
				.thenThrow(buildOpenSearchException("mapper_parsing_exception", "bad mapping"));

			// call under test
			RuntimeException ex = assertThrows(RuntimeException.class,
				() -> manager.createIndex(INDEX_NAME, buildTestColumns(), "org-scientific",
					Collections.emptyList(), Collections.emptyList(), buildStandardAnalyzers()));

			assertTrue(ex.getMessage().contains("Failed to create search index"));
		}

		@Test
		public void testCreateIndexWithIOException() throws IOException {
			when(openSearchClient.indices()).thenReturn(indicesClient);
			when(indicesClient.create(any(CreateIndexRequest.class)))
				.thenThrow(new IOException("Connection refused"));

			// call under test
			RuntimeException ex = assertThrows(RuntimeException.class,
				() -> manager.createIndex(INDEX_NAME, buildTestColumns(), "org-scientific",
					Collections.emptyList(), Collections.emptyList(), buildStandardAnalyzers()));

			assertTrue(ex.getMessage().contains("Failed to create search index"));
		}

		@Test
		public void testCreateIndexWithEquivalentSynonymRules() throws IOException {
			when(openSearchClient.indices()).thenReturn(indicesClient);
			CreateIndexResponse response = new CreateIndexResponse.Builder()
				.index(INDEX_NAME).acknowledged(true).shardsAcknowledged(true).build();
			when(indicesClient.create(any(CreateIndexRequest.class))).thenReturn(response);

			SynonymRule rule = new SynonymRule();
			rule.setRuleType(SynonymRuleType.EQUIVALENT);
			rule.setTerms(Arrays.asList("cancer", "carcinoma", "neoplasm"));
			SynonymSet synonymSet = new SynonymSet();
			synonymSet.setRules(Arrays.asList(rule));

			ArgumentCaptor<CreateIndexRequest> captor = ArgumentCaptor.forClass(CreateIndexRequest.class);

			// call under test
			String result = manager.createIndex(INDEX_NAME, buildTestColumns(), "org-scientific",
				Arrays.asList(synonymSet), Collections.emptyList(), buildStandardAnalyzers());

			verify(indicesClient).create(captor.capture());
			String requestJson = captor.getValue().toJsonString();
			assertTrue(requestJson.contains("cancer, carcinoma, neoplasm"),
				"Expected equivalent synonym rule in request JSON: " + requestJson);
		}

		@Test
		public void testCreateIndexWithExplicitSynonymRules() throws IOException {
			when(openSearchClient.indices()).thenReturn(indicesClient);
			CreateIndexResponse response = new CreateIndexResponse.Builder()
				.index(INDEX_NAME).acknowledged(true).shardsAcknowledged(true).build();
			when(indicesClient.create(any(CreateIndexRequest.class))).thenReturn(response);

			SynonymRule rule = new SynonymRule();
			rule.setRuleType(SynonymRuleType.EXPLICIT);
			rule.setTerms(Arrays.asList("AD", "Alzheimer's disease", "Alzheimers"));
			SynonymSet synonymSet = new SynonymSet();
			synonymSet.setRules(Arrays.asList(rule));

			ArgumentCaptor<CreateIndexRequest> captor = ArgumentCaptor.forClass(CreateIndexRequest.class);

			// call under test
			manager.createIndex(INDEX_NAME, buildTestColumns(), "org-scientific",
				Arrays.asList(synonymSet), Collections.emptyList(), buildStandardAnalyzers());

			verify(indicesClient).create(captor.capture());
			String requestJson = captor.getValue().toJsonString();
			assertTrue(requestJson.contains("AD => Alzheimer's disease, Alzheimers"),
				"Expected explicit synonym rule in request JSON: " + requestJson);
		}

		@Test
		public void testCreateIndexWithSynonymRuleLessThan2TermsSkipped() throws IOException {
			when(openSearchClient.indices()).thenReturn(indicesClient);
			CreateIndexResponse response = new CreateIndexResponse.Builder()
				.index(INDEX_NAME).acknowledged(true).shardsAcknowledged(true).build();
			when(indicesClient.create(any(CreateIndexRequest.class))).thenReturn(response);

			SynonymRule rule = new SynonymRule();
			rule.setRuleType(SynonymRuleType.EQUIVALENT);
			rule.setTerms(Arrays.asList("lonely"));
			SynonymSet synonymSet = new SynonymSet();
			synonymSet.setRules(Arrays.asList(rule));

			ArgumentCaptor<CreateIndexRequest> captor = ArgumentCaptor.forClass(CreateIndexRequest.class);

			// call under test
			manager.createIndex(INDEX_NAME, buildTestColumns(), "org-scientific",
				Arrays.asList(synonymSet), Collections.emptyList(), buildStandardAnalyzers());

			verify(indicesClient).create(captor.capture());
			String requestJson = captor.getValue().toJsonString();
			// No synonym filter should be registered because the only rule was skipped
			assertTrue(!requestJson.contains("synapse_synonyms"),
				"Single-term synonym rule should be skipped: " + requestJson);
		}

		@Test
		public void testCreateIndexWithNullSynonymSetRulesSkipped() throws IOException {
			when(openSearchClient.indices()).thenReturn(indicesClient);
			CreateIndexResponse response = new CreateIndexResponse.Builder()
				.index(INDEX_NAME).acknowledged(true).shardsAcknowledged(true).build();
			when(indicesClient.create(any(CreateIndexRequest.class))).thenReturn(response);

			SynonymSet synonymSet = new SynonymSet();
			synonymSet.setRules(null);

			// call under test
			String result = manager.createIndex(INDEX_NAME, buildTestColumns(), "org-scientific",
				Arrays.asList(synonymSet), Collections.emptyList(), buildStandardAnalyzers());

			assertNotNull(result);
		}

		@Test
		public void testCreateIndexWithColumnAnalyzerOverrides() throws IOException {
			when(openSearchClient.indices()).thenReturn(indicesClient);
			CreateIndexResponse response = new CreateIndexResponse.Builder()
				.index(INDEX_NAME).acknowledged(true).shardsAcknowledged(true).build();
			when(indicesClient.create(any(CreateIndexRequest.class))).thenReturn(response);

			ColumnAnalyzerOverrideEntry entry = new ColumnAnalyzerOverrideEntry();
			entry.setColumnName("studyName");
			entry.setIndexAnalyzer("org-standard");
			ColumnAnalyzerOverride override = new ColumnAnalyzerOverride();
			override.setOverrides(Arrays.asList(entry));

			ArgumentCaptor<CreateIndexRequest> captor = ArgumentCaptor.forClass(CreateIndexRequest.class);

			// call under test
			manager.createIndex(INDEX_NAME, buildTestColumns(), "org-scientific",
				Collections.emptyList(), Arrays.asList(override), buildStandardAnalyzers());

			verify(indicesClient).create(captor.capture());
			String requestJson = captor.getValue().toJsonString();
			// The overridden column "studyName" (id=123) should use analyzer for id=2 (org-standard)
			assertTrue(requestJson.contains("synapse_analyzer_2"),
				"Expected overridden analyzer in request JSON: " + requestJson);
		}
	}

	// ---- DeleteIndex tests ----

	@Nested
	class DeleteIndexTests {

		@Test
		public void testDeleteIndexWithSuccess() throws IOException {
			when(openSearchClient.indices()).thenReturn(indicesClient);
			DeleteIndexResponse response = DeleteIndexResponse.of(r -> r.acknowledged(true));
			when(indicesClient.delete(any(DeleteIndexRequest.class))).thenReturn(response);

			// call under test
			assertDoesNotThrow(() -> manager.deleteIndex(INDEX_NAME));
		}

		@Test
		public void testDeleteIndexWithIndexNotFound() throws IOException {
			when(openSearchClient.indices()).thenReturn(indicesClient);
			when(indicesClient.delete(any(DeleteIndexRequest.class)))
				.thenThrow(buildOpenSearchException("index_not_found_exception", "no such index"));

			// call under test - should not throw
			assertDoesNotThrow(() -> manager.deleteIndex(INDEX_NAME));
			verifyNoMoreInteractions(indicesClient);
		}

		@Test
		public void testDeleteIndexWithOpenSearchException() throws IOException {
			when(openSearchClient.indices()).thenReturn(indicesClient);
			when(indicesClient.delete(any(DeleteIndexRequest.class)))
				.thenThrow(buildOpenSearchException("security_exception", "unauthorized"));

			// call under test
			RuntimeException ex = assertThrows(RuntimeException.class,
				() -> manager.deleteIndex(INDEX_NAME));

			assertTrue(ex.getMessage().contains("Failed to delete search index"));
		}

		@Test
		public void testDeleteIndexWithIOException() throws IOException {
			when(openSearchClient.indices()).thenReturn(indicesClient);
			when(indicesClient.delete(any(DeleteIndexRequest.class)))
				.thenThrow(new IOException("Connection reset"));

			// call under test
			RuntimeException ex = assertThrows(RuntimeException.class,
				() -> manager.deleteIndex(INDEX_NAME));

			assertTrue(ex.getMessage().contains("Failed to delete search index"));
		}
	}

	// ---- BulkIndex tests ----

	@Nested
	class BulkIndexTests {

		@Test
		public void testBulkIndexWithEmptyList() throws IOException {
			// call under test
			long result = manager.bulkIndex(INDEX_NAME, Collections.emptyList());

			assertEquals(0L, result);
			verifyZeroInteractions(openSearchClient);
		}

		@Test
		public void testBulkIndexWithSuccess() throws IOException {
			List<Map<String, Object>> docs = Arrays.asList(
				createDoc(1L, 1L, "val1"),
				createDoc(2L, 1L, "val2"),
				createDoc(3L, 1L, "val3")
			);

			BulkResponse bulkResponse = new BulkResponse.Builder()
				.items(Arrays.asList(
					buildBulkItem("1", 201, null),
					buildBulkItem("2", 201, null),
					buildBulkItem("3", 201, null)))
				.errors(false)
				.took(10L)
				.build();
			when(openSearchClient.bulk(any(BulkRequest.class))).thenReturn(bulkResponse);

			// call under test
			long result = manager.bulkIndex(INDEX_NAME, docs);

			assertEquals(3L, result);
		}

		@Test
		public void testBulkIndexWithPartialErrors() throws IOException {
			List<Map<String, Object>> docs = Arrays.asList(
				createDoc(1L, 1L, "val1"),
				createDoc(2L, 1L, "val2")
			);

			ErrorCause errorCause = ErrorCause.of(ec -> ec.type("mapper_parsing_exception").reason("bad field"));
			BulkResponse bulkResponse = new BulkResponse.Builder()
				.items(Arrays.asList(
					buildBulkItem("1", 201, null),
					buildBulkItemWithError("2", 400, errorCause)))
				.errors(true)
				.took(10L)
				.build();
			when(openSearchClient.bulk(any(BulkRequest.class))).thenReturn(bulkResponse);

			// call under test
			RuntimeException ex = assertThrows(RuntimeException.class,
				() -> manager.bulkIndex(INDEX_NAME, docs));

			assertTrue(ex.getMessage().contains("1 document(s) rejected out of 2"));
		}

		@Test
		public void testBulkIndexWithOpenSearchException() throws IOException {
			List<Map<String, Object>> docs = Arrays.asList(createDoc(1L, 1L, "val1"));
			when(openSearchClient.bulk(any(BulkRequest.class)))
				.thenThrow(buildOpenSearchException("security_exception", "no permission"));

			// call under test
			RuntimeException ex = assertThrows(RuntimeException.class,
				() -> manager.bulkIndex(INDEX_NAME, docs));

			assertTrue(ex.getMessage().contains("Failed to bulk index"));
		}

		@Test
		public void testBulkIndexWithIOException() throws IOException {
			List<Map<String, Object>> docs = Arrays.asList(createDoc(1L, 1L, "val1"));
			when(openSearchClient.bulk(any(BulkRequest.class)))
				.thenThrow(new IOException("Timeout"));

			// call under test
			RuntimeException ex = assertThrows(RuntimeException.class,
				() -> manager.bulkIndex(INDEX_NAME, docs));

			assertTrue(ex.getMessage().contains("Failed to bulk index"));
		}

		private Map<String, Object> createDoc(Long rowId, Long rowVersion, String fieldValue) {
			Map<String, Object> doc = new HashMap<>();
			doc.put("_row_id", rowId);
			doc.put("_row_version", rowVersion);
			doc.put("123", fieldValue);
			return doc;
		}

		private BulkResponseItem buildBulkItem(String id, int status, ErrorCause error) {
			return new BulkResponseItem.Builder()
				.index(INDEX_NAME)
				.id(id)
				.status(status)
				.result("created")
				.operationType(OperationType.Index)
				.build();
		}

		private BulkResponseItem buildBulkItemWithError(String id, int status, ErrorCause error) {
			return new BulkResponseItem.Builder()
				.index(INDEX_NAME)
				.id(id)
				.status(status)
				.error(error)
				.operationType(OperationType.Index)
				.build();
		}
	}

	// ---- Search tests ----

	@Nested
	class SearchTests {

		private SearchQuery buildBasicQuery(String text) {
			SearchQuery query = new SearchQuery();
			query.setQueryText(text);
			return query;
		}

		@SuppressWarnings("rawtypes")
		private void setupSearchMock(SearchResponse<Map> response) throws IOException {
			when(openSearchClient.search(any(SearchRequest.class), eq(Map.class)))
				.thenReturn(response);
		}

		@Test
		public void testSearchWithBasicQuery() throws IOException {
			Map<String, Object> source = new HashMap<>();
			source.put("_row_id", 42);
			source.put("_row_version", 1);
			source.put("123", "Alzheimer's study");
			setupSearchMock(buildSearchResponse(1L, Arrays.asList(buildHit("42", 1.5, source)), null));

			SearchQuery query = buildBasicQuery("Alzheimer");

			// call under test
			SearchQueryResults results = manager.search(INDEX_NAME, query, buildTestColumns(),
				"org-scientific", Collections.emptyList(), buildStandardAnalyzers());

			assertEquals(1L, results.getTotalHits());
			assertEquals(0L, results.getOffset());
			assertEquals(1, results.getHits().size());

			SearchHit hit = results.getHits().get(0);
			assertEquals(42L, hit.getRowId());
			assertEquals(1L, hit.getRowVersion());
			assertEquals(1.5, hit.getScore());
		}

		@Test
		public void testSearchWithNullQueryTextForcesMatchAll() throws IOException {
			setupSearchMock(buildSearchResponse(0L, Collections.emptyList(), null));

			SearchQuery query = buildBasicQuery(null);

			// call under test
			SearchQueryResults results = manager.search(INDEX_NAME, query, buildTestColumns(),
				"org-scientific", Collections.emptyList(), buildStandardAnalyzers());

			assertNotNull(results);
			assertEquals(0L, results.getTotalHits());
		}

		@Test
		public void testSearchWithEmptyQueryTextForcesMatchAll() throws IOException {
			setupSearchMock(buildSearchResponse(0L, Collections.emptyList(), null));

			SearchQuery query = buildBasicQuery("   ");

			// call under test
			SearchQueryResults results = manager.search(INDEX_NAME, query, buildTestColumns(),
				"org-scientific", Collections.emptyList(), buildStandardAnalyzers());

			assertNotNull(results);
		}

		@Test
		public void testSearchWithMatchQueryType() throws IOException {
			setupSearchMock(buildSearchResponse(0L, Collections.emptyList(), null));

			SearchQuery query = buildBasicQuery("cancer");
			query.setQueryType(SearchQueryType.MATCH);
			query.setQueryFields(Arrays.asList("studyName"));

			// call under test
			SearchQueryResults results = manager.search(INDEX_NAME, query, buildTestColumns(),
				"org-scientific", Collections.emptyList(), buildStandardAnalyzers());

			assertNotNull(results);
		}

		@Test
		public void testSearchWithMultiMatchQueryType() throws IOException {
			setupSearchMock(buildSearchResponse(0L, Collections.emptyList(), null));

			SearchQuery query = buildBasicQuery("cancer");
			query.setQueryType(SearchQueryType.MULTI_MATCH);
			query.setFuzziness("AUTO");

			// call under test
			SearchQueryResults results = manager.search(INDEX_NAME, query, buildTestColumns(),
				"org-scientific", Collections.emptyList(), buildStandardAnalyzers());

			assertNotNull(results);
		}

		@Test
		public void testSearchWithMatchPhraseQueryType() throws IOException {
			setupSearchMock(buildSearchResponse(0L, Collections.emptyList(), null));

			SearchQuery query = buildBasicQuery("breast cancer");
			query.setQueryType(SearchQueryType.MATCH_PHRASE);
			query.setQueryFields(Arrays.asList("studyName"));

			// call under test
			SearchQueryResults results = manager.search(INDEX_NAME, query, buildTestColumns(),
				"org-scientific", Collections.emptyList(), buildStandardAnalyzers());

			assertNotNull(results);
		}

		@Test
		public void testSearchWithPrefixQueryType() throws IOException {
			setupSearchMock(buildSearchResponse(0L, Collections.emptyList(), null));

			SearchQuery query = buildBasicQuery("alz");
			query.setQueryType(SearchQueryType.PREFIX);

			// call under test
			SearchQueryResults results = manager.search(INDEX_NAME, query, buildTestColumns(),
				"org-scientific", Collections.emptyList(), buildStandardAnalyzers());

			assertNotNull(results);
		}

		@Test
		public void testSearchWithWildcardQueryType() throws IOException {
			setupSearchMock(buildSearchResponse(0L, Collections.emptyList(), null));

			SearchQuery query = buildBasicQuery("can*er");
			query.setQueryType(SearchQueryType.WILDCARD);
			query.setQueryFields(Arrays.asList("studyName"));

			// call under test
			SearchQueryResults results = manager.search(INDEX_NAME, query, buildTestColumns(),
				"org-scientific", Collections.emptyList(), buildStandardAnalyzers());

			assertNotNull(results);
		}

		@Test
		public void testSearchWithMatchAllQueryType() throws IOException {
			setupSearchMock(buildSearchResponse(0L, Collections.emptyList(), null));

			SearchQuery query = buildBasicQuery("anything");
			query.setQueryType(SearchQueryType.MATCH_ALL);

			// call under test
			SearchQueryResults results = manager.search(INDEX_NAME, query, buildTestColumns(),
				"org-scientific", Collections.emptyList(), buildStandardAnalyzers());

			assertNotNull(results);
		}

		@Test
		public void testSearchWithDefaultLimitAndOffset() throws IOException {
			setupSearchMock(buildSearchResponse(0L, Collections.emptyList(), null));

			SearchQuery query = buildBasicQuery("test");
			// leave limit and offset null

			// call under test
			SearchQueryResults results = manager.search(INDEX_NAME, query, buildTestColumns(),
				"org-scientific", Collections.emptyList(), buildStandardAnalyzers());

			assertEquals(0L, results.getOffset());
		}

		@Test
		public void testSearchWithLimitCapping() throws IOException {
			setupSearchMock(buildSearchResponse(0L, Collections.emptyList(), null));

			SearchQuery query = buildBasicQuery("test");
			query.setLimit(500L);
			query.setOffset(10L);

			// call under test
			SearchQueryResults results = manager.search(INDEX_NAME, query, buildTestColumns(),
				"org-scientific", Collections.emptyList(), buildStandardAnalyzers());

			// Limit is capped at 100 internally; we verify the call doesn't fail and offset is set
			assertEquals(10L, results.getOffset());
		}

		@Test
		public void testSearchWithTermsFilter() throws IOException {
			setupSearchMock(buildSearchResponse(0L, Collections.emptyList(), null));

			SearchQuery query = buildBasicQuery("test");
			KeyValues kv = new KeyValues();
			kv.setKey("disease");
			kv.setValues(Arrays.asList("cancer", "diabetes"));
			query.setTermsFilters(Arrays.asList(kv));

			// call under test
			SearchQueryResults results = manager.search(INDEX_NAME, query, buildTestColumns(),
				"org-scientific", Collections.emptyList(), buildStandardAnalyzers());

			assertNotNull(results);
		}

		@Test
		public void testSearchWithNegatedTermsFilter() throws IOException {
			setupSearchMock(buildSearchResponse(0L, Collections.emptyList(), null));

			SearchQuery query = buildBasicQuery("test");
			KeyValues kv = new KeyValues();
			kv.setKey("disease");
			kv.setValues(Arrays.asList("healthy"));
			kv.setNot(true);
			query.setTermsFilters(Arrays.asList(kv));

			// call under test
			SearchQueryResults results = manager.search(INDEX_NAME, query, buildTestColumns(),
				"org-scientific", Collections.emptyList(), buildStandardAnalyzers());

			assertNotNull(results);
		}

		@Test
		public void testSearchWithRangeFilter() throws IOException {
			setupSearchMock(buildSearchResponse(0L, Collections.emptyList(), null));

			SearchQuery query = buildBasicQuery("test");
			KeyRange kr = new KeyRange();
			kr.setKey("age");
			kr.setMin("18");
			kr.setMax("65");
			query.setRangeFilters(Arrays.asList(kr));

			// call under test
			SearchQueryResults results = manager.search(INDEX_NAME, query, buildTestColumns(),
				"org-scientific", Collections.emptyList(), buildStandardAnalyzers());

			assertNotNull(results);
		}

		@Test
		public void testSearchWithRangeFilterMinOnly() throws IOException {
			setupSearchMock(buildSearchResponse(0L, Collections.emptyList(), null));

			SearchQuery query = buildBasicQuery("test");
			KeyRange kr = new KeyRange();
			kr.setKey("age");
			kr.setMin("18");
			query.setRangeFilters(Arrays.asList(kr));

			// call under test
			SearchQueryResults results = manager.search(INDEX_NAME, query, buildTestColumns(),
				"org-scientific", Collections.emptyList(), buildStandardAnalyzers());

			assertNotNull(results);
		}

		@Test
		public void testSearchWithExistsFilter() throws IOException {
			setupSearchMock(buildSearchResponse(0L, Collections.emptyList(), null));

			SearchQuery query = buildBasicQuery("test");
			query.setExistsFilters(Arrays.asList("disease"));

			// call under test
			SearchQueryResults results = manager.search(INDEX_NAME, query, buildTestColumns(),
				"org-scientific", Collections.emptyList(), buildStandardAnalyzers());

			assertNotNull(results);
		}

		@Test
		public void testSearchWithNotExistsFilter() throws IOException {
			setupSearchMock(buildSearchResponse(0L, Collections.emptyList(), null));

			SearchQuery query = buildBasicQuery("test");
			query.setNotExistsFilters(Arrays.asList("disease"));

			// call under test
			SearchQueryResults results = manager.search(INDEX_NAME, query, buildTestColumns(),
				"org-scientific", Collections.emptyList(), buildStandardAnalyzers());

			assertNotNull(results);
		}

		@Test
		public void testSearchWithFacetRequests() throws IOException {
			Map<String, Aggregate> aggregations = new HashMap<>();
			aggregations.put("456", Aggregate.of(a -> a.sterms(st -> st
				.buckets(b -> b.array(Arrays.asList(
					StringTermsBucket.of(sb -> sb.key("cancer").docCount(10)),
					StringTermsBucket.of(sb -> sb.key("diabetes").docCount(5))
				))))));
			setupSearchMock(buildSearchResponse(15L, Collections.emptyList(), aggregations));

			SearchQuery query = buildBasicQuery("test");
			FacetRequest facet = new FacetRequest();
			facet.setColumnName("disease");
			facet.setMaxValueCount(10L);
			facet.setSortField(FacetSortField.KEY);
			facet.setSortDirection(SortDirection.ASC);
			query.setFacetRequests(Arrays.asList(facet));

			// call under test
			SearchQueryResults results = manager.search(INDEX_NAME, query, buildTestColumns(),
				"org-scientific", Collections.emptyList(), buildStandardAnalyzers());

			assertNotNull(results.getFacets());
			assertEquals(1, results.getFacets().size());
			assertTrue(results.getFacets().get(0) instanceof org.sagebionetworks.repo.model.table.FacetColumnResultValues);
			org.sagebionetworks.repo.model.table.FacetColumnResultValues facetResult =
				(org.sagebionetworks.repo.model.table.FacetColumnResultValues) results.getFacets().get(0);
			assertEquals("disease", facetResult.getColumnName());
			assertEquals(FacetType.enumeration, facetResult.getFacetType());
			assertEquals(2, facetResult.getFacetValues().size());
			assertEquals("cancer", facetResult.getFacetValues().get(0).getValue());
			assertEquals(10L, facetResult.getFacetValues().get(0).getCount());
			assertEquals(false, facetResult.getFacetValues().get(0).getIsSelected());
		}

		@Test
		public void testSearchWithFacetDefaultSortByCount() throws IOException {
			Map<String, Aggregate> aggregations = new HashMap<>();
			aggregations.put("456", Aggregate.of(a -> a.sterms(st -> st
				.buckets(b -> b.array(Arrays.asList(
					StringTermsBucket.of(sb -> sb.key("cancer").docCount(10))
				))))));
			setupSearchMock(buildSearchResponse(10L, Collections.emptyList(), aggregations));

			SearchQuery query = buildBasicQuery("test");
			FacetRequest facet = new FacetRequest();
			facet.setColumnName("disease");
			// leave sortField and sortDirection null for defaults
			query.setFacetRequests(Arrays.asList(facet));

			// call under test
			SearchQueryResults results = manager.search(INDEX_NAME, query, buildTestColumns(),
				"org-scientific", Collections.emptyList(), buildStandardAnalyzers());

			assertNotNull(results.getFacets());
		}

		@Test
		public void testSearchWithHighlights() throws IOException {
			Map<String, Object> source = new HashMap<>();
			source.put("_row_id", 1);
			source.put("_row_version", 1);
			source.put("123", "Alzheimer study");

			Map<String, List<String>> highlights = new HashMap<>();
			highlights.put("123", Arrays.asList("<em>Alzheimer</em> study"));

			setupSearchMock(buildSearchResponse(1L,
				Arrays.asList(buildHitWithHighlights("1", 1.0, source, highlights)), null));

			SearchQuery query = buildBasicQuery("Alzheimer");
			query.setHighlight(true);

			// call under test
			SearchQueryResults results = manager.search(INDEX_NAME, query, buildTestColumns(),
				"org-scientific", Collections.emptyList(), buildStandardAnalyzers());

			SearchHit hit = results.getHits().get(0);
			assertNotNull(hit.getHighlights());
			assertEquals(1, hit.getHighlights().size());
			assertEquals("studyName", hit.getHighlights().get(0).getName());
			assertTrue(hit.getHighlights().get(0).getValue().contains("<em>Alzheimer</em>"));
		}

		@Test
		public void testSearchWithHighlightSearchableSubFieldStripped() throws IOException {
			Map<String, Object> source = new HashMap<>();
			source.put("_row_id", 1);
			source.put("_row_version", 1);
			source.put("101", "http://example.com");

			Map<String, List<String>> highlights = new HashMap<>();
			highlights.put("101.searchable", Arrays.asList("<em>example</em>"));

			setupSearchMock(buildSearchResponse(1L,
				Arrays.asList(buildHitWithHighlights("1", 1.0, source, highlights)), null));

			SearchQuery query = buildBasicQuery("example");
			query.setHighlight(true);

			// call under test
			SearchQueryResults results = manager.search(INDEX_NAME, query, buildTestColumns(),
				"org-scientific", Collections.emptyList(), buildStandardAnalyzers());

			SearchHit hit = results.getHits().get(0);
			assertNotNull(hit.getHighlights());
			// The ".searchable" suffix should be stripped, and "101" mapped to "url"
			assertEquals("url", hit.getHighlights().get(0).getName());
		}

		@Test
		public void testSearchWithCustomSort() throws IOException {
			setupSearchMock(buildSearchResponse(0L, Collections.emptyList(), null));

			SearchQuery query = buildBasicQuery("test");
			SortField sf = new SortField();
			sf.setColumnName("age");
			sf.setDirection(SortDirection.ASC);
			query.setSort(Arrays.asList(sf));

			// call under test
			SearchQueryResults results = manager.search(INDEX_NAME, query, buildTestColumns(),
				"org-scientific", Collections.emptyList(), buildStandardAnalyzers());

			assertNotNull(results);
		}

		@Test
		public void testSearchWithScoreSort() throws IOException {
			setupSearchMock(buildSearchResponse(0L, Collections.emptyList(), null));

			SearchQuery query = buildBasicQuery("test");
			SortField sf = new SortField();
			sf.setColumnName("_score");
			sf.setDirection(SortDirection.DESC);
			query.setSort(Arrays.asList(sf));

			// call under test
			SearchQueryResults results = manager.search(INDEX_NAME, query, buildTestColumns(),
				"org-scientific", Collections.emptyList(), buildStandardAnalyzers());

			assertNotNull(results);
		}

		@Test
		public void testSearchWithReturnFields() throws IOException {
			setupSearchMock(buildSearchResponse(0L, Collections.emptyList(), null));

			SearchQuery query = buildBasicQuery("test");
			query.setReturnFields(Arrays.asList("studyName", "disease"));

			// call under test
			SearchQueryResults results = manager.search(INDEX_NAME, query, buildTestColumns(),
				"org-scientific", Collections.emptyList(), buildStandardAnalyzers());

			assertNotNull(results);
		}

		@Test
		public void testSearchWithQueryFieldsAndBoost() throws IOException {
			setupSearchMock(buildSearchResponse(0L, Collections.emptyList(), null));

			SearchQuery query = buildBasicQuery("cancer");
			query.setQueryType(SearchQueryType.MULTI_MATCH);
			query.setQueryFields(Arrays.asList("studyName^3", "disease^1"));

			// call under test
			SearchQueryResults results = manager.search(INDEX_NAME, query, buildTestColumns(),
				"org-scientific", Collections.emptyList(), buildStandardAnalyzers());

			assertNotNull(results);
		}

		@Test
		public void testSearchWithIndexNotFoundExceptionThrowsIllegalState() throws IOException {
			when(openSearchClient.search(any(SearchRequest.class), eq(Map.class)))
				.thenThrow(buildOpenSearchException("index_not_found_exception", "no such index"));

			SearchQuery query = buildBasicQuery("test");

			// call under test
			IllegalStateException ex = assertThrows(IllegalStateException.class,
				() -> manager.search(INDEX_NAME, query, buildTestColumns(),
					"org-scientific", Collections.emptyList(), buildStandardAnalyzers()));

			assertTrue(ex.getMessage().contains("still building"));
		}

		@Test
		public void testSearchWithOpenSearchException() throws IOException {
			when(openSearchClient.search(any(SearchRequest.class), eq(Map.class)))
				.thenThrow(buildOpenSearchException("query_parsing_exception", "bad query"));

			SearchQuery query = buildBasicQuery("test");

			// call under test
			RuntimeException ex = assertThrows(RuntimeException.class,
				() -> manager.search(INDEX_NAME, query, buildTestColumns(),
					"org-scientific", Collections.emptyList(), buildStandardAnalyzers()));

			assertTrue(ex.getMessage().contains("Failed to execute search"));
		}

		@Test
		public void testSearchWithIOException() throws IOException {
			when(openSearchClient.search(any(SearchRequest.class), eq(Map.class)))
				.thenThrow(new IOException("Timeout"));

			SearchQuery query = buildBasicQuery("test");

			// call under test
			RuntimeException ex = assertThrows(RuntimeException.class,
				() -> manager.search(INDEX_NAME, query, buildTestColumns(),
					"org-scientific", Collections.emptyList(), buildStandardAnalyzers()));

			assertTrue(ex.getMessage().contains("Failed to execute search"));
		}
	}

	// ---- Response conversion tests ----

	@Nested
	class ResponseConversionTests {

		@SuppressWarnings("rawtypes")
		private void setupSearchMock(SearchResponse<Map> response) throws IOException {
			when(openSearchClient.search(any(SearchRequest.class), eq(Map.class)))
				.thenReturn(response);
		}

		@Test
		public void testSearchResponseConversionWithFields() throws IOException {
			Map<String, Object> source = new HashMap<>();
			source.put("_row_id", 42);
			source.put("_row_version", 3);
			source.put("123", "Alzheimer's study");
			source.put("789", 65);
			setupSearchMock(buildSearchResponse(1L, Arrays.asList(buildHit("42", 2.5, source)), null));

			SearchQuery query = new SearchQuery();
			query.setQueryText("test");

			// call under test
			SearchQueryResults results = manager.search(INDEX_NAME, query, buildTestColumns(),
				"org-scientific", Collections.emptyList(), buildStandardAnalyzers());

			SearchHit hit = results.getHits().get(0);
			assertEquals(42L, hit.getRowId());
			assertEquals(3L, hit.getRowVersion());

			// Fields should exclude system fields and map IDs to names
			List<SearchFieldValue> fields = hit.getFields();
			assertNotNull(fields);
			boolean hasStudyName = fields.stream().anyMatch(f -> "studyName".equals(f.getName()) && "Alzheimer's study".equals(f.getValue()));
			boolean hasAge = fields.stream().anyMatch(f -> "age".equals(f.getName()) && "65".equals(f.getValue()));
			assertTrue(hasStudyName, "Expected studyName field");
			assertTrue(hasAge, "Expected age field");
			// System fields should not appear as user fields
			boolean hasRowId = fields.stream().anyMatch(f -> "_row_id".equals(f.getName()));
			assertTrue(!hasRowId, "_row_id should be filtered from fields");
		}

		@Test
		public void testSearchResponseConversionWithNullSource() throws IOException {
			@SuppressWarnings("rawtypes")
			Hit<Map> hitWithNullSource = Hit.of(h -> h.id("1").index(INDEX_NAME).score(1.0));
			setupSearchMock(buildSearchResponse(1L, Arrays.asList(hitWithNullSource), null));

			SearchQuery query = new SearchQuery();
			query.setQueryText("test");

			// call under test
			SearchQueryResults results = manager.search(INDEX_NAME, query, buildTestColumns(),
				"org-scientific", Collections.emptyList(), buildStandardAnalyzers());

			SearchHit hit = results.getHits().get(0);
			assertNull(hit.getRowId());
			assertNull(hit.getFields());
		}

		@Test
		public void testSearchResponseConversionWithRowIdAsString() throws IOException {
			Map<String, Object> source = new HashMap<>();
			source.put("_row_id", "99");
			source.put("_row_version", "7");
			setupSearchMock(buildSearchResponse(1L, Arrays.asList(buildHit("99", 1.0, source)), null));

			SearchQuery query = new SearchQuery();
			query.setQueryText("test");

			// call under test
			SearchQueryResults results = manager.search(INDEX_NAME, query, buildTestColumns(),
				"org-scientific", Collections.emptyList(), buildStandardAnalyzers());

			SearchHit hit = results.getHits().get(0);
			assertEquals(99L, hit.getRowId());
			assertEquals(7L, hit.getRowVersion());
		}

		@Test
		public void testSearchResponseConversionWithInvalidRowIdReturnsNull() throws IOException {
			Map<String, Object> source = new HashMap<>();
			source.put("_row_id", "not-a-number");
			source.put("_row_version", 1);
			setupSearchMock(buildSearchResponse(1L, Arrays.asList(buildHit("1", 1.0, source)), null));

			SearchQuery query = new SearchQuery();
			query.setQueryText("test");

			// call under test
			SearchQueryResults results = manager.search(INDEX_NAME, query, buildTestColumns(),
				"org-scientific", Collections.emptyList(), buildStandardAnalyzers());

			SearchHit hit = results.getHits().get(0);
			assertNull(hit.getRowId());
		}

		@Test
		public void testSearchResponseConversionWithNullFieldValue() throws IOException {
			Map<String, Object> source = new HashMap<>();
			source.put("_row_id", 1);
			source.put("_row_version", 1);
			source.put("123", null);
			setupSearchMock(buildSearchResponse(1L, Arrays.asList(buildHit("1", 1.0, source)), null));

			SearchQuery query = new SearchQuery();
			query.setQueryText("test");

			// call under test
			SearchQueryResults results = manager.search(INDEX_NAME, query, buildTestColumns(),
				"org-scientific", Collections.emptyList(), buildStandardAnalyzers());

			SearchHit hit = results.getHits().get(0);
			SearchFieldValue field = hit.getFields().stream()
				.filter(f -> "studyName".equals(f.getName()))
				.findFirst().orElse(null);
			assertNotNull(field);
			assertNull(field.getValue());
		}

		@Test
		public void testSearchResponseConversionWithLtermsAggregation() throws IOException {
			Map<String, Aggregate> aggregations = new HashMap<>();
			aggregations.put("789", Aggregate.of(a -> a.lterms(lt -> lt
				.buckets(b -> b.array(Arrays.asList(
					org.opensearch.client.opensearch._types.aggregations.LongTermsBucket.of(
						lb -> lb.key(org.opensearch.client.opensearch._types.aggregations.LongTermsBucketKey.of(k -> k.signed(25L))).keyAsString("25").docCount(8))
				))))));
			setupSearchMock(buildSearchResponse(8L, Collections.emptyList(), aggregations));

			SearchQuery query = new SearchQuery();
			query.setQueryText("test");
			FacetRequest facet = new FacetRequest();
			facet.setColumnName("age");
			query.setFacetRequests(Arrays.asList(facet));

			// call under test
			SearchQueryResults results = manager.search(INDEX_NAME, query, buildTestColumns(),
				"org-scientific", Collections.emptyList(), buildStandardAnalyzers());

			assertNotNull(results.getFacets());
			assertEquals(1, results.getFacets().size());
			org.sagebionetworks.repo.model.table.FacetColumnResultValues facetResult =
				(org.sagebionetworks.repo.model.table.FacetColumnResultValues) results.getFacets().get(0);
			assertEquals("age", facetResult.getColumnName());
			assertEquals("25", facetResult.getFacetValues().get(0).getValue());
		}

		@Test
		public void testSearchResponseConversionWithDtermsAggregation() throws IOException {
			// Add a DOUBLE column for this test
			List<ColumnModel> columns = new ArrayList<>(buildTestColumns());
			columns.add(buildColumn("200", "weight", ColumnType.DOUBLE));

			Map<String, Aggregate> aggregations = new HashMap<>();
			aggregations.put("200", Aggregate.of(a -> a.dterms(dt -> dt
				.buckets(b -> b.array(Arrays.asList(
					org.opensearch.client.opensearch._types.aggregations.DoubleTermsBucket.of(
						db -> db.key(72.5).docCount(3))
				))))));
			setupSearchMock(buildSearchResponse(3L, Collections.emptyList(), aggregations));

			SearchQuery query = new SearchQuery();
			query.setQueryText("test");
			FacetRequest facet = new FacetRequest();
			facet.setColumnName("weight");
			query.setFacetRequests(Arrays.asList(facet));

			// call under test
			SearchQueryResults results = manager.search(INDEX_NAME, query, columns,
				"org-scientific", Collections.emptyList(), buildStandardAnalyzers());

			assertNotNull(results.getFacets());
			assertEquals("weight", ((org.sagebionetworks.repo.model.table.FacetColumnResultValues) results.getFacets().get(0)).getColumnName());
		}
	}

	// ---- Autocomplete tests ----

	@Nested
	class AutocompleteTests {

		@SuppressWarnings("rawtypes")
		private void setupSearchMock(SearchResponse<Map> response) throws IOException {
			when(openSearchClient.search(any(SearchRequest.class), eq(Map.class)))
				.thenReturn(response);
		}

		@Test
		public void testAutocompleteForcesPrefixType() throws IOException {
			setupSearchMock(buildSearchResponse(0L, Collections.emptyList(), null));

			SearchQuery query = new SearchQuery();
			query.setQueryText("alz");
			query.setQueryType(SearchQueryType.MATCH);

			// call under test
			manager.autocomplete(INDEX_NAME, query, buildTestColumns(),
				"org-scientific", Collections.emptyList(), buildStandardAnalyzers());

			assertEquals(SearchQueryType.PREFIX, query.getQueryType());
		}

		@Test
		public void testAutocompleteCapsLimitAt8() throws IOException {
			setupSearchMock(buildSearchResponse(0L, Collections.emptyList(), null));

			SearchQuery query = new SearchQuery();
			query.setQueryText("alz");
			query.setLimit(50L);

			// call under test
			manager.autocomplete(INDEX_NAME, query, buildTestColumns(),
				"org-scientific", Collections.emptyList(), buildStandardAnalyzers());

			assertEquals(8L, query.getLimit());
		}

		@Test
		public void testAutocompleteKeepsLimitWhenUnderMax() throws IOException {
			setupSearchMock(buildSearchResponse(0L, Collections.emptyList(), null));

			SearchQuery query = new SearchQuery();
			query.setQueryText("alz");
			query.setLimit(5L);

			// call under test
			manager.autocomplete(INDEX_NAME, query, buildTestColumns(),
				"org-scientific", Collections.emptyList(), buildStandardAnalyzers());

			assertEquals(5L, query.getLimit());
		}

		@Test
		public void testAutocompleteWithNullLimit() throws IOException {
			setupSearchMock(buildSearchResponse(0L, Collections.emptyList(), null));

			SearchQuery query = new SearchQuery();
			query.setQueryText("alz");
			query.setLimit(null);

			// call under test
			manager.autocomplete(INDEX_NAME, query, buildTestColumns(),
				"org-scientific", Collections.emptyList(), buildStandardAnalyzers());

			assertEquals(8L, query.getLimit());
		}
	}

	// ---- resolveEffectiveAnalyzerName tests ----

	@Nested
	class ResolveEffectiveAnalyzerNameTests {

		@Test
		public void testResolveEffectiveAnalyzerNameWithOverride() {
			Map<String, ColumnAnalyzerOverrideEntry> overrideMap = new HashMap<>();
			ColumnAnalyzerOverrideEntry entry = new ColumnAnalyzerOverrideEntry();
			entry.setIndexAnalyzer("org-standard");
			overrideMap.put("123", entry);

			Map<Long, String> idToQualifiedName = Map.of(1L, "org-scientific", 2L, "org-standard");

			// call under test
			String result = manager.resolveEffectiveAnalyzerName("123", ColumnType.STRING,
				"org-scientific", overrideMap, idToQualifiedName);

			assertEquals("org-standard", result);
		}

		@Test
		public void testResolveEffectiveAnalyzerNameWithDefaultAnalyzer() {
			Map<Long, String> idToQualifiedName = Map.of(1L, "org-scientific");

			// call under test
			String result = manager.resolveEffectiveAnalyzerName("123", ColumnType.STRING,
				"org-scientific", Collections.emptyMap(), idToQualifiedName);

			assertEquals("org-scientific", result);
		}

		@Test
		public void testResolveEffectiveAnalyzerNameWithColumnTypeDefault() {
			Map<Long, String> idToQualifiedName = Map.of(1L, "org-scientific");

			// call under test
			String result = manager.resolveEffectiveAnalyzerName("123", ColumnType.STRING,
				null, Collections.emptyMap(), idToQualifiedName);

			// STRING defaults to SCIENTIFIC_ID = 1
			assertEquals("org-scientific", result);
		}

		@Test
		public void testResolveEffectiveAnalyzerNameWithOverrideEntryButNullIndexAnalyzer() {
			Map<String, ColumnAnalyzerOverrideEntry> overrideMap = new HashMap<>();
			ColumnAnalyzerOverrideEntry entry = new ColumnAnalyzerOverrideEntry();
			entry.setIndexAnalyzer(null);
			overrideMap.put("123", entry);

			Map<Long, String> idToQualifiedName = Map.of(1L, "org-scientific");

			// call under test
			String result = manager.resolveEffectiveAnalyzerName("123", ColumnType.STRING,
				"org-scientific", overrideMap, idToQualifiedName);

			// Falls through to defaultAnalyzer
			assertEquals("org-scientific", result);
		}
	}

	// ---- buildIdToQualifiedNameMap tests ----

	@Nested
	class BuildIdToQualifiedNameMapTests {

		@Test
		public void testBuildIdToQualifiedNameMapWithMultipleAnalyzers() {
			Map<String, TextAnalyzer> analyzers = buildStandardAnalyzers();

			// call under test
			Map<Long, String> result = OpenSearchManagerImpl.buildIdToQualifiedNameMap(analyzers);

			assertEquals("org-scientific", result.get(1L));
			assertEquals("org-standard", result.get(2L));
			assertEquals("org-keyword", result.get(4L));
		}

		@Test
		public void testBuildIdToQualifiedNameMapWithEmptyMap() {
			// call under test
			Map<Long, String> result = OpenSearchManagerImpl.buildIdToQualifiedNameMap(Collections.emptyMap());

			assertTrue(result.isEmpty());
		}
	}
}
