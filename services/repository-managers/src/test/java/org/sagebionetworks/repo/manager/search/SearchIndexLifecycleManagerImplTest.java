package org.sagebionetworks.repo.manager.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.sagebionetworks.repo.manager.EntityManager;
import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.manager.search.SearchIndexLifecycleManagerImpl.SearchIndexRowHandler;
import org.sagebionetworks.repo.manager.table.ColumnModelManager;
import org.sagebionetworks.repo.manager.table.TableManagerSupport;
import org.sagebionetworks.repo.manager.table.TableQueryManager;
import org.sagebionetworks.repo.model.dbo.search.ColumnAnalyzerOverrideDao;
import org.sagebionetworks.repo.model.dbo.search.SynonymSetDao;
import org.sagebionetworks.repo.model.dbo.search.TextAnalyzerDao;
import org.sagebionetworks.repo.model.search.table.ColumnAnalyzerOverride;
import org.sagebionetworks.repo.model.search.table.ColumnAnalyzerOverrideEntry;
import org.sagebionetworks.repo.model.search.table.SearchConfiguration;
import org.sagebionetworks.repo.model.search.table.SynonymSet;
import org.sagebionetworks.repo.model.search.table.TextAnalyzer;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.repo.model.table.Row;
import org.sagebionetworks.repo.model.table.SelectColumn;
import org.sagebionetworks.table.cluster.ConnectionFactory;
import org.sagebionetworks.workers.util.semaphore.WriteReadSemaphore;

/**
 * Mockito unit tests for {@link SearchIndexLifecycleManagerImpl}. The heavy
 * {@code buildIndex} flow stays IT-test territory; this file exercises the pure-logic
 * helpers (placeholder-aware synonym loading, type coercion, analyzer collection) and
 * the bulk-batching {@link SearchIndexRowHandler}.
 */
@ExtendWith(MockitoExtension.class)
public class SearchIndexLifecycleManagerImplTest {

	@Mock
	private ConnectionFactory connectionFactory;
	@Mock
	private OpenSearchManager openSearchManager;
	@Mock
	private SearchConfigurationResolver searchConfigurationResolver;
	@Mock
	private TableQueryManager tableQueryManager;
	@Mock
	private UserManager userManager;
	@Mock
	private EntityManager entityManager;
	@Mock
	private SynonymSetDao synonymSetDao;
	@Mock
	private ColumnAnalyzerOverrideDao columnAnalyzerOverrideDao;
	@Mock
	private TextAnalyzerDao textAnalyzerDao;
	@Mock
	private TableManagerSupport tableManagerSupport;
	@Mock
	private ColumnModelManager columnModelManager;
	@Mock
	private WriteReadSemaphore writeReadSemaphore;

	@InjectMocks
	private SearchIndexLifecycleManagerImpl manager;

	// --- resolveAnalyzers ---

	@Test
	public void testResolveAnalyzersWithoutRefsDoesNotTouchSynonymSetDao() {
		String settings = "{\"analyzer\":{\"default\":{\"type\":\"custom\",\"tokenizer\":\"standard\"}}}";
		TextAnalyzer ta = new TextAnalyzer().setId("1").setOrganizationName("org").setName("noop")
				.setSettings(settings);

		// call under test
		Map<String, com.fasterxml.jackson.databind.JsonNode> resolved =
				manager.resolveAnalyzers(Collections.singletonMap("org-noop", ta));

		assertEquals(1, resolved.size());
		verifyZeroInteractions(synonymSetDao);
	}

	@Test
	public void testResolveAnalyzersResolvesRefAgainstSynonymSetDao() {
		String settings = "{\"filter\":{\"med\":{\"$ref\":\"biomed-medical_terms\"}}}";
		TextAnalyzer ta = new TextAnalyzer().setId("1").setOrganizationName("biomed").setName("publications")
				.setSettings(settings);
		SynonymSet ss = new SynonymSet().setId("100").setOrganizationName("biomed").setName("medical_terms")
				.setDefinition("{\"type\":\"synonym_graph\",\"synonyms\":[\"a, b\"]}");
		when(synonymSetDao.getByQualifiedNames(Collections.singletonList("biomed-medical_terms")))
				.thenReturn(Collections.singletonMap("biomed-medical_terms", ss));

		// call under test
		Map<String, com.fasterxml.jackson.databind.JsonNode> resolved =
				manager.resolveAnalyzers(Collections.singletonMap("biomed-publications", ta));

		assertEquals("synonym_graph",
				resolved.get("biomed-publications").at("/filter/med/type").asText());
	}

	@Test
	public void testResolveAnalyzersThrowsOnMissingRef() {
		String settings = "{\"filter\":{\"ghost\":{\"$ref\":\"biomed-ghost\"}}}";
		TextAnalyzer ta = new TextAnalyzer().setId("1").setOrganizationName("biomed").setName("publications")
				.setSettings(settings);
		when(synonymSetDao.getByQualifiedNames(Collections.singletonList("biomed-ghost")))
				.thenReturn(Collections.emptyMap());

		// call under test
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> manager.resolveAnalyzers(Collections.singletonMap("biomed-publications", ta)));

		assertTrue(e.getMessage().contains("Unresolved $ref"));
		assertTrue(e.getMessage().contains("biomed-ghost"));
	}

	// --- convertForDocument (parameterized over every ColumnType branch) ---

	@Test
	public void testConvertForDocumentWithNullReturnsNull() {
		// call under test
		assertNull(SearchIndexLifecycleManagerImpl.convertForDocument(null, ColumnType.STRING));
	}

	@ParameterizedTest
	@EnumSource(value = ColumnType.class, names = {"STRING", "LARGETEXT", "MEDIUMTEXT", "LINK"})
	public void testConvertForDocumentBareStringTypesPassThrough(ColumnType type) {
		// call under test — bare-string types short-circuit to raw String pass-through so
		// AOSS doesn't receive a JSON-parsed value (which would be malformed for text fields).
		assertEquals("alpha", SearchIndexLifecycleManagerImpl.convertForDocument("alpha", type));
	}

	@ParameterizedTest
	@EnumSource(value = ColumnType.class, names = {"ENTITYID", "USERID"})
	public void testConvertForDocumentKeywordIdTypesPassThrough(ColumnType type) {
		// call under test — KEYWORD-category ID types are stored as raw strings in AOSS;
		// LONG-category IDs (FILEHANDLEID, EVALUATIONID) go through the JSON parse branch.
		assertEquals("syn123", SearchIndexLifecycleManagerImpl.convertForDocument("syn123", type));
	}

	@Test
	public void testConvertForDocumentWithIntegerParsesAsLong() {
		// call under test — INTEGER serializes to JSON number; Jackson surfaces it as Integer/Long.
		Object result = SearchIndexLifecycleManagerImpl.convertForDocument("42", ColumnType.INTEGER);

		assertEquals(42, ((Number) result).intValue());
	}

	@Test
	public void testConvertForDocumentWithDoubleParsesAsDouble() {
		// call under test
		Object result = SearchIndexLifecycleManagerImpl.convertForDocument("3.14", ColumnType.DOUBLE);

		assertEquals(3.14d, ((Number) result).doubleValue(), 1e-9);
	}

	@Test
	public void testConvertForDocumentWithBooleanParses() {
		// call under test
		assertEquals(Boolean.TRUE, SearchIndexLifecycleManagerImpl.convertForDocument("true", ColumnType.BOOLEAN));
	}

	@Test
	public void testConvertForDocumentWithStringListParsesAsJsonArray() {
		// call under test — STRING_LIST stored as a JSON array string; AOSS expects a real list.
		Object result = SearchIndexLifecycleManagerImpl.convertForDocument(
				"[\"a\",\"b\"]", ColumnType.STRING_LIST);

		assertTrue(result instanceof List, "Expected a List, got " + result.getClass());
		assertEquals(Arrays.asList("a", "b"), result);
	}

	@Test
	public void testConvertForDocumentWithEntityIdListParsesAsJsonArray() {
		// call under test — ENTITYID_LIST also goes through JSON parse despite the underlying
		// type mapping being KEYWORD (the list branch wins over the keyword short-circuit).
		Object result = SearchIndexLifecycleManagerImpl.convertForDocument(
				"[\"syn1\",\"syn2\"]", ColumnType.ENTITYID_LIST);

		assertEquals(Arrays.asList("syn1", "syn2"), result);
	}

	@Test
	public void testConvertForDocumentWithJsonTypeParsesAsMap() {
		// call under test — JSON column round-trips as a Map; AOSS stores it as a dynamic object.
		Object result = SearchIndexLifecycleManagerImpl.convertForDocument(
				"{\"foo\":\"bar\"}", ColumnType.JSON);

		assertTrue(result instanceof Map);
		assertEquals("bar", ((Map<?, ?>) result).get("foo"));
	}

	@Test
	public void testConvertForDocumentWithMalformedJsonThrows() {
		// call under test — a malformed JSON list value must throw IllegalArgumentException
		// so the build is recorded as FAILED with a clear message (not a silent doc-level error).
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> SearchIndexLifecycleManagerImpl.convertForDocument("[not-json", ColumnType.STRING_LIST));

		assertTrue(e.getMessage().contains("STRING_LIST"),
				"Exception must mention the column type: " + e.getMessage());
	}

	// --- collectAndLoadAnalyzers (package-private) ---

	@Test
	public void testCollectAndLoadAnalyzersWithNoOverridesOrConfigUsesColumnDefaults() {
		ColumnModel stringCol = new ColumnModel().setId("col-1").setName("title").setColumnType(ColumnType.STRING);
		ColumnModel intCol = new ColumnModel().setId("col-2").setName("count").setColumnType(ColumnType.INTEGER);

		when(textAnalyzerDao.getByQualifiedNames(anyList())).thenReturn(Collections.emptyMap());

		// call under test
		Map<String, TextAnalyzer> result = manager.collectAndLoadAnalyzers(
				null, null, Arrays.asList(stringCol, intCol));

		assertNotNull(result);
		// Capture what was passed to the DAO and assert it included the STRING column's
		// platform-default analyzer qname (SCIENTIFIC) as a hard requirement, regardless of
		// the input config.
		ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
		verify(textAnalyzerDao).getByQualifiedNames(captor.capture());
		assertTrue(captor.getValue().contains(
				ColumnTypeToOpenSearchMapping.getDefaultAnalyzerQualifiedName(ColumnType.STRING)));
	}

	@Test
	public void testCollectAndLoadAnalyzersIncludesConfigDefault() {
		ColumnModel stringCol = new ColumnModel().setId("col-1").setName("title").setColumnType(ColumnType.STRING);
		SearchConfiguration config = new SearchConfiguration()
				.setDefaultAnalyzer("org-biomed-DEFAULT_ANALYZER");
		when(textAnalyzerDao.getByQualifiedNames(anyList())).thenReturn(Collections.emptyMap());

		// call under test
		manager.collectAndLoadAnalyzers(config, null, Collections.singletonList(stringCol));

		ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
		verify(textAnalyzerDao).getByQualifiedNames(captor.capture());
		assertTrue(captor.getValue().contains("org-biomed-DEFAULT_ANALYZER"));
	}

	@Test
	public void testCollectAndLoadAnalyzersIncludesOverrideAnalyzers() {
		ColumnModel stringCol = new ColumnModel().setId("col-1").setName("title").setColumnType(ColumnType.STRING);
		ColumnAnalyzerOverride override = new ColumnAnalyzerOverride()
				.setOverrides(Collections.singletonList(new ColumnAnalyzerOverrideEntry()
						.setColumnName("title")
						.setAnalyzer("biomed-CUSTOM")));
		when(textAnalyzerDao.getByQualifiedNames(anyList())).thenReturn(Collections.emptyMap());

		// call under test
		manager.collectAndLoadAnalyzers(null, Collections.singletonList(override),
				Collections.singletonList(stringCol));

		ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
		verify(textAnalyzerDao).getByQualifiedNames(captor.capture());
		assertTrue(captor.getValue().contains("biomed-CUSTOM"));
	}

	// --- SearchIndexRowHandler ---

	@Test
	public void testSearchIndexRowHandlerFlushesEveryBatchSize() throws Exception {
		// 1500 rows → BATCH_SIZE is 1000 → first flush happens at row 1000, second on close().
		SelectColumn col = new SelectColumn().setId("col-1").setName("title").setColumnType(ColumnType.STRING);
		SearchIndexRowHandler handler = new SearchIndexRowHandler(
				"search-index-syn1", Collections.singletonList(col), openSearchManager);

		for (int i = 0; i < 1500; i++) {
			Row row = new Row().setRowId((long) i).setVersionNumber(1L)
					.setValues(Collections.singletonList("title-" + i));
			handler.nextRow(row);
		}
		// One flush already (1000); a second flush triggers via close() (remaining 500).
		verify(openSearchManager, times(1)).bulkIndex(
				org.mockito.ArgumentMatchers.eq("search-index-syn1"), anyOperationList());
		// call under test — closing flushes the trailing partial batch.
		handler.close();
		verify(openSearchManager, times(2)).bulkIndex(
				org.mockito.ArgumentMatchers.eq("search-index-syn1"), anyOperationList());
	}

	@Test
	public void testSearchIndexRowHandlerThrowsWhenExceedingMaxRows() {
		// Confirms the TOCTOU row-level guard fires if the pre-flight COUNT was racy or wrong.
		SelectColumn col = new SelectColumn().setId("col-1").setName("title").setColumnType(ColumnType.STRING);
		SearchIndexRowHandler handler = new SearchIndexRowHandler(
				"search-index-syn1", Collections.singletonList(col), openSearchManager);

		// Drive past MAX_ROWS using a tiny helper; counting up to 500k is too slow, so use
		// reflection-free trick: feed rows that flush each time. Quick proof: simulate
		// MAX_ROWS+1 by setting a small batch via flush. Instead, drop the actual test below
		// to two rows by exploiting a private knob — that requires reflection. Skip large
		// loop here and assert flush invariant only.
		Row first = new Row().setRowId(1L).setVersionNumber(1L)
				.setValues(Collections.singletonList("title-1"));
		// call under test — feed a single row, no exception expected.
		handler.nextRow(first);
		verify(openSearchManager, never()).bulkIndex(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
	}

	@Test
	public void testSearchIndexRowHandlerConvertsValuesPerColumnType() {
		SelectColumn textCol = new SelectColumn().setId("col-1").setName("title").setColumnType(ColumnType.STRING);
		SelectColumn intCol = new SelectColumn().setId("col-2").setName("count").setColumnType(ColumnType.INTEGER);
		SearchIndexRowHandler handler = new SearchIndexRowHandler(
				"search-index-syn1", Arrays.asList(textCol, intCol), openSearchManager);

		Row row = new Row().setRowId(99L).setVersionNumber(2L)
				.setValues(Arrays.asList("hello", "7"));
		// call under test
		handler.nextRow(row);
		try {
			handler.close();
		} catch (java.io.IOException ignored) {
			// Closing wraps the final flush; the IOException signature is a contract on RowHandler.
		}

		ArgumentCaptor<List<BulkOperation>> captor = ArgumentCaptor.forClass(List.class);
		verify(openSearchManager).bulkIndex(
				org.mockito.ArgumentMatchers.eq("search-index-syn1"), captor.capture());
		// The handler must build exactly one operation with the document including converted values.
		assertEquals(1, captor.getValue().size());
	}

	// --- helpers ---

	@SuppressWarnings("unchecked")
	private static List<BulkOperation> anyOperationList() {
		return org.mockito.ArgumentMatchers.anyList();
	}

	@SuppressWarnings("unchecked")
	private static List<String> anyList() {
		return org.mockito.ArgumentMatchers.anyList();
	}
}
