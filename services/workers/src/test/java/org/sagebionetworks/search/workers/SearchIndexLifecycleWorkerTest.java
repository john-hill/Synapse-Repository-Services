package org.sagebionetworks.search.workers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.manager.EntityManager;
import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.manager.search.SearchConfigurationResolver;
import org.sagebionetworks.repo.manager.search.OpenSearchManager;
import org.sagebionetworks.repo.manager.table.TableManagerSupport;
import org.sagebionetworks.repo.manager.table.TableQueryManager;
import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.EntityType;
import org.sagebionetworks.repo.model.NodeDAO;
import org.sagebionetworks.repo.model.ObjectType;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dbo.search.ColumnAnalyzerOverrideDao;
import org.sagebionetworks.repo.model.dbo.search.SynonymSetDao;
import org.sagebionetworks.repo.model.dbo.search.TextAnalyzerDao;
import org.sagebionetworks.repo.manager.search.ColumnTypeToOpenSearchMapping;
import org.sagebionetworks.repo.model.entity.IdAndVersion;
import org.sagebionetworks.repo.model.jdo.KeyFactory;
import org.sagebionetworks.repo.model.message.ChangeMessage;
import org.sagebionetworks.repo.model.message.ChangeType;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.repo.model.table.QueryResultBundle;
import org.sagebionetworks.repo.model.table.TableState;
import org.sagebionetworks.repo.model.search.table.ColumnAnalyzerOverride;
import org.sagebionetworks.repo.model.search.table.ColumnAnalyzerOverrideEntry;
import org.sagebionetworks.repo.model.search.table.SearchConfiguration;
import org.sagebionetworks.repo.model.search.table.SearchIndex;
import org.sagebionetworks.repo.model.search.table.SearchIndexState;
import org.sagebionetworks.repo.model.search.table.TextAnalyzer;
import org.sagebionetworks.repo.web.NotFoundException;
import org.sagebionetworks.table.cluster.ConnectionFactory;
import org.sagebionetworks.table.cluster.search.SearchIndexStatusDao;
import org.sagebionetworks.util.progress.ProgressCallback;
import org.sagebionetworks.workers.util.aws.message.RecoverableMessageException;

@ExtendWith(MockitoExtension.class)
public class SearchIndexLifecycleWorkerTest {

	@Mock
	private NodeDAO nodeDao;
	@Mock
	private ConnectionFactory connectionFactory;
	@Mock
	private OpenSearchManager openSearchManager;
	@Mock
	private SearchConfigurationResolver searchConfigurationResolver;
	@Mock
	private TableManagerSupport tableManagerSupport;
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
	private ProgressCallback progressCallback;
	@Mock
	private SearchIndexStatusDao statusDao;

	private SearchIndexLifecycleWorker worker;

	@BeforeEach
	public void setUp() {
		worker = new SearchIndexLifecycleWorker(
				nodeDao, connectionFactory, openSearchManager,
				searchConfigurationResolver, tableManagerSupport,
				tableQueryManager, userManager,
				entityManager, synonymSetDao, columnAnalyzerOverrideDao,
				textAnalyzerDao);
	}

	private void setupConnectionFactory() {
		when(connectionFactory.getSearchIndexStatusDao()).thenReturn(statusDao);
	}

	private ChangeMessage entityMessage(String id, ChangeType type) {
		ChangeMessage msg = new ChangeMessage();
		msg.setObjectType(ObjectType.ENTITY);
		msg.setObjectId(id);
		msg.setChangeType(type);
		return msg;
	}

	@Test
	public void testSkipsNonEntityMessages() throws Exception {
		ChangeMessage msg = new ChangeMessage();
		msg.setObjectType(ObjectType.TABLE);
		msg.setObjectId("syn123");
		msg.setChangeType(ChangeType.CREATE);

		worker.run(progressCallback, Collections.singletonList(msg));

		verifyZeroInteractions(nodeDao);
	}

	@Test
	public void testSkipsNonSearchIndexEntities() throws Exception {
		setupConnectionFactory();
		when(nodeDao.getNodeTypeById("syn123")).thenReturn(EntityType.file);

		worker.run(progressCallback, Collections.singletonList(entityMessage("syn123", ChangeType.CREATE)));

		verifyZeroInteractions(statusDao);
	}

	@Test
	public void testCreateSetsCreatingThenActive() throws Exception {
		setupConnectionFactory();
		String entityId = "syn456";
		when(nodeDao.getNodeTypeById(entityId)).thenReturn(EntityType.searchindex);
		setupBuildMocks(entityId);

		worker.run(progressCallback, Collections.singletonList(entityMessage(entityId, ChangeType.CREATE)));

		verify(statusDao).createOrUpdate(KeyFactory.stringToKey(entityId), SearchIndexState.CREATING, null, null);
		verify(statusDao).createOrUpdate(eq(KeyFactory.stringToKey(entityId)), eq(SearchIndexState.ACTIVE), (String) isNull(), nullable(String.class));
	}

	@Test
	public void testUpdateAlwaysBuilds() throws Exception {
		setupConnectionFactory();
		String entityId = "syn456";
		when(nodeDao.getNodeTypeById(entityId)).thenReturn(EntityType.searchindex);
		setupBuildMocks(entityId);

		worker.run(progressCallback, Collections.singletonList(entityMessage(entityId, ChangeType.UPDATE)));

		verify(statusDao).createOrUpdate(KeyFactory.stringToKey(entityId), SearchIndexState.CREATING, null, null);
		verify(statusDao).createOrUpdate(eq(KeyFactory.stringToKey(entityId)), eq(SearchIndexState.ACTIVE), (String) isNull(), nullable(String.class));
	}

	@Test
	public void testDeleteSetsDeleteAndCleans() throws Exception {
		setupConnectionFactory();
		String entityId = "syn456";
		when(nodeDao.getNodeTypeById(entityId)).thenReturn(EntityType.searchindex);

		worker.run(progressCallback, Collections.singletonList(entityMessage(entityId, ChangeType.DELETE)));

		verify(statusDao).createOrUpdate(KeyFactory.stringToKey(entityId), SearchIndexState.DELETING, null, null);
		verify(openSearchManager).deleteIndex("search-index-" + entityId);
		verify(statusDao).delete(KeyFactory.stringToKey(entityId));
	}

	@Test
	public void testEntityNotFoundCleansUp() throws Exception {
		setupConnectionFactory();
		String entityId = "syn456";
		when(nodeDao.getNodeTypeById(entityId)).thenThrow(new NotFoundException("not found"));

		worker.run(progressCallback, Collections.singletonList(entityMessage(entityId, ChangeType.UPDATE)));

		verify(openSearchManager).deleteIndex("search-index-" + entityId);
		verify(statusDao).delete(KeyFactory.stringToKey(entityId));
	}

	@Test
	public void testBuildFailureSetsFailedStatus() throws Exception {
		setupConnectionFactory();
		String entityId = "syn456";
		when(nodeDao.getNodeTypeById(entityId)).thenReturn(EntityType.searchindex);

		UserInfo adminUser = new UserInfo(true);
		when(userManager.getUserInfo(
				AuthorizationConstants.BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId()))
				.thenReturn(adminUser);

		SearchIndex searchIndex = new SearchIndex();
		searchIndex.setId(entityId);
		searchIndex.setDefiningSQL("SELECT * FROM syn789");
		searchIndex.setParentId("syn100");
		when(entityManager.getEntity(adminUser, entityId, SearchIndex.class)).thenReturn(searchIndex);
		when(tableManagerSupport.getTableStatusState(IdAndVersion.parse("syn789")))
				.thenReturn(Optional.of(TableState.AVAILABLE));
		when(searchConfigurationResolver.resolve(adminUser, null, "syn100"))
				.thenReturn(Optional.empty());

		UserInfo anonUser = new UserInfo(false);
		when(userManager.getUserInfo(
				AuthorizationConstants.BOOTSTRAP_PRINCIPAL.ANONYMOUS_USER.getPrincipalId()))
				.thenReturn(anonUser);

		String errorMsg = "Something went wrong";
		when(tableQueryManager.querySinglePage(any(), any(), any(), any()))
				.thenThrow(new RuntimeException(errorMsg));

		worker.run(progressCallback, Collections.singletonList(entityMessage(entityId, ChangeType.CREATE)));

		verify(statusDao).createOrUpdate(KeyFactory.stringToKey(entityId), SearchIndexState.CREATING, null, null);
		verify(statusDao).createOrUpdate(KeyFactory.stringToKey(entityId), SearchIndexState.FAILED, errorMsg, null);
		// Verify OpenSearch was never called since the failure happened before index creation
		verifyZeroInteractions(openSearchManager);
	}

	// --- Dependency checking tests ---

	@Test
	public void testSourceProcessing_throwsRecoverableMessageException() throws Exception {
		setupConnectionFactory();
		String entityId = "syn456";
		when(nodeDao.getNodeTypeById(entityId)).thenReturn(EntityType.searchindex);

		UserInfo adminUser = new UserInfo(true);
		when(userManager.getUserInfo(
				AuthorizationConstants.BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId()))
				.thenReturn(adminUser);

		SearchIndex searchIndex = new SearchIndex();
		searchIndex.setId(entityId);
		searchIndex.setDefiningSQL("SELECT * FROM syn789");
		searchIndex.setParentId("syn100");
		when(entityManager.getEntity(adminUser, entityId, SearchIndex.class)).thenReturn(searchIndex);

		// Source table is still processing
		when(tableManagerSupport.getTableStatusState(IdAndVersion.parse("syn789")))
				.thenReturn(Optional.of(TableState.PROCESSING));

		assertThrows(RecoverableMessageException.class, () ->
				worker.run(progressCallback, Collections.singletonList(entityMessage(entityId, ChangeType.CREATE))));

		// CREATING status should NOT have been set
		verify(statusDao, never()).createOrUpdate(any(), eq(SearchIndexState.CREATING), any(), any());
	}

	@Test
	public void testSourceProcessingFailed_setsFailedStatus() throws Exception {
		setupConnectionFactory();
		String entityId = "syn456";
		when(nodeDao.getNodeTypeById(entityId)).thenReturn(EntityType.searchindex);

		UserInfo adminUser = new UserInfo(true);
		when(userManager.getUserInfo(
				AuthorizationConstants.BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId()))
				.thenReturn(adminUser);

		SearchIndex searchIndex = new SearchIndex();
		searchIndex.setId(entityId);
		searchIndex.setDefiningSQL("SELECT * FROM syn789");
		searchIndex.setParentId("syn100");
		when(entityManager.getEntity(adminUser, entityId, SearchIndex.class)).thenReturn(searchIndex);

		// Source table has failed
		when(tableManagerSupport.getTableStatusState(IdAndVersion.parse("syn789")))
				.thenReturn(Optional.of(TableState.PROCESSING_FAILED));

		worker.run(progressCallback, Collections.singletonList(entityMessage(entityId, ChangeType.CREATE)));

		// Should set FAILED status (IllegalStateException caught by handleCreate)
		verify(statusDao).createOrUpdate(eq(KeyFactory.stringToKey(entityId)), eq(SearchIndexState.FAILED),
				argThat(s -> s != null && s.contains("PROCESSING_FAILED")), isNull());
		// CREATING status should NOT have been set
		verify(statusDao, never()).createOrUpdate(any(), eq(SearchIndexState.CREATING), any(), any());
	}

	@Test
	public void testSourceAvailable_proceedsWithBuild() throws Exception {
		setupConnectionFactory();
		String entityId = "syn456";
		when(nodeDao.getNodeTypeById(entityId)).thenReturn(EntityType.searchindex);
		setupBuildMocks(entityId);

		worker.run(progressCallback, Collections.singletonList(entityMessage(entityId, ChangeType.CREATE)));

		// Should proceed normally with CREATING then ACTIVE
		verify(statusDao).createOrUpdate(KeyFactory.stringToKey(entityId), SearchIndexState.CREATING, null, null);
		verify(statusDao).createOrUpdate(eq(KeyFactory.stringToKey(entityId)), eq(SearchIndexState.ACTIVE), (String) isNull(), nullable(String.class));
	}

	@Test
	public void testRecoverableMessageException_propagatesFromProcessMessage() throws Exception {
		setupConnectionFactory();
		String entityId = "syn456";
		when(nodeDao.getNodeTypeById(entityId)).thenReturn(EntityType.searchindex);

		UserInfo adminUser = new UserInfo(true);
		when(userManager.getUserInfo(
				AuthorizationConstants.BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId()))
				.thenReturn(adminUser);

		SearchIndex searchIndex = new SearchIndex();
		searchIndex.setId(entityId);
		searchIndex.setDefiningSQL("SELECT * FROM syn789");
		searchIndex.setParentId("syn100");
		when(entityManager.getEntity(adminUser, entityId, SearchIndex.class)).thenReturn(searchIndex);

		// Source has no status yet
		when(tableManagerSupport.getTableStatusState(IdAndVersion.parse("syn789")))
				.thenReturn(Optional.empty());

		// RecoverableMessageException should propagate all the way through
		// processMessage and run, not be swallowed
		RecoverableMessageException ex = assertThrows(RecoverableMessageException.class, () ->
				worker.run(progressCallback, Collections.singletonList(entityMessage(entityId, ChangeType.CREATE))));
		assertEquals("Source entity syn789 has no status yet. Deferring search index build.", ex.getMessage());
	}

	@Test
	public void testCheckSourceTableReady_noFromClause() throws Exception {
		// SQL without a recognizable FROM syn... clause should not throw
		worker.checkSourceTableReady("SELECT 1");
	}

	@Test
	public void testCheckSourceTableReady_withVersion() throws Exception {
		when(tableManagerSupport.getTableStatusState(IdAndVersion.parse("syn789.3")))
				.thenReturn(Optional.of(TableState.AVAILABLE));

		// Should not throw
		worker.checkSourceTableReady("SELECT * FROM syn789.3");
	}

	// --- Boundary and limit verification ---

	@ParameterizedTest(name = "rowCount={0}, shouldBuild={1}")
	@CsvSource({
		"0, true",
		"499999, true",
		"500000, true",
		"500001, false"
	})
	void testPreFlightCountBoundary(long rowCount, boolean shouldBuild) throws Exception {
		String entityId = "syn1";
		ChangeMessage msg = entityMessage(entityId, ChangeType.CREATE);
		setupConnectionFactory();
		when(nodeDao.getNodeTypeById(entityId)).thenReturn(EntityType.searchindex);
		setupBuildMocks(entityId);

		// Override the count result from setupBuildMocks
		QueryResultBundle countResult = new QueryResultBundle();
		countResult.setQueryCount(rowCount);
		when(tableQueryManager.querySinglePage(any(), any(), any(), any())).thenReturn(countResult);

		worker.run(progressCallback, List.of(msg));

		if (shouldBuild) {
			verify(statusDao).createOrUpdate(eq(KeyFactory.stringToKey(entityId)), eq(SearchIndexState.ACTIVE), (String) isNull(), nullable(String.class));
		} else {
			verify(statusDao).createOrUpdate(eq(KeyFactory.stringToKey(entityId)), eq(SearchIndexState.FAILED), argThat(s -> s != null && s.contains("500")), isNull());
		}
	}

	// Error message truncation
	@Test
	void testErrorMessageTruncatedTo3000Chars() throws Exception {
		String entityId = "syn1";
		ChangeMessage msg = entityMessage(entityId, ChangeType.CREATE);
		setupConnectionFactory();
		when(nodeDao.getNodeTypeById(entityId)).thenReturn(EntityType.searchindex);
		setupBuildMocks(entityId);

		// Build a 5000-character error message
		StringBuilder longMessage = new StringBuilder();
		for (int i = 0; i < 5000; i++) {
			longMessage.append('x');
		}
		when(tableQueryManager.querySinglePage(any(), any(), any(), any()))
			.thenThrow(new RuntimeException(longMessage.toString()));

		worker.run(progressCallback, List.of(msg));

		ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);
		verify(statusDao).createOrUpdate(eq(KeyFactory.stringToKey(entityId)), eq(SearchIndexState.FAILED), errorCaptor.capture(), isNull());
		assertEquals(3000, errorCaptor.getValue().length());
	}

	// Worker idempotency (duplicate CREATE then UPDATE)
	@Test
	void testDuplicateCreateMessageIsIdempotent() throws Exception {
		String entityId = "syn1";
		ChangeMessage msg1 = entityMessage(entityId, ChangeType.CREATE);
		setupConnectionFactory();
		when(nodeDao.getNodeTypeById(entityId)).thenReturn(EntityType.searchindex);
		setupBuildMocks(entityId);

		// First CREATE builds
		worker.run(progressCallback, List.of(msg1));
		verify(statusDao).createOrUpdate(KeyFactory.stringToKey(entityId), SearchIndexState.CREATING, null, null);
		verify(statusDao).createOrUpdate(eq(KeyFactory.stringToKey(entityId)), eq(SearchIndexState.ACTIVE), (String) isNull(), nullable(String.class));

		// Reset and setup for second call
		reset(statusDao);
		when(connectionFactory.getSearchIndexStatusDao()).thenReturn(statusDao);
		setupBuildMocks(entityId);

		ChangeMessage updateMsg = entityMessage(entityId, ChangeType.UPDATE);
		worker.run(progressCallback, List.of(updateMsg));

		// UPDATE always rebuilds (upsert is idempotent)
		verify(statusDao).createOrUpdate(KeyFactory.stringToKey(entityId), SearchIndexState.CREATING, null, null);
		verify(statusDao).createOrUpdate(eq(KeyFactory.stringToKey(entityId)), eq(SearchIndexState.ACTIVE), (String) isNull(), nullable(String.class));
	}

	// Out-of-order messages - UPDATE before CREATE
	@Test
	void testUpdateBeforeCreateBuildsIndex() throws Exception {
		// UPDATE message arrives before CREATE (common SQS anomaly)
		// Worker always builds on UPDATE
		String entityId = "syn1";
		ChangeMessage msg = entityMessage(entityId, ChangeType.UPDATE);
		setupConnectionFactory();
		when(nodeDao.getNodeTypeById(entityId)).thenReturn(EntityType.searchindex);
		setupBuildMocks(entityId);

		worker.run(progressCallback, List.of(msg));

		verify(statusDao).createOrUpdate(KeyFactory.stringToKey(entityId), SearchIndexState.CREATING, null, null);
		verify(statusDao).createOrUpdate(eq(KeyFactory.stringToKey(entityId)), eq(SearchIndexState.ACTIVE), (String) isNull(), nullable(String.class));
	}

	// Out-of-order messages - DELETE while CREATING
	@Test
	void testDeleteWhileCreating() throws Exception {
		// DELETE arrives while status is CREATING
		// Worker should still clean up
		String entityId = "syn1";
		ChangeMessage msg = entityMessage(entityId, ChangeType.DELETE);
		setupConnectionFactory();
		when(nodeDao.getNodeTypeById(entityId)).thenReturn(EntityType.searchindex);

		worker.run(progressCallback, List.of(msg));

		verify(statusDao).createOrUpdate(KeyFactory.stringToKey(entityId), SearchIndexState.DELETING, null, null);
		verify(openSearchManager).deleteIndex("search-index-" + entityId);
		verify(statusDao).delete(KeyFactory.stringToKey(entityId));
	}

	@Test
	void testCollectAndLoadAnalyzersWithConfigOverridesAndDefaults() {
		// Setup a config with a default analyzer and overrides referencing specific analyzers
		SearchConfiguration config = new SearchConfiguration();
		config.setDefaultAnalyzer("myorg-CUSTOM_DEFAULT");

		ColumnAnalyzerOverrideEntry entry = new ColumnAnalyzerOverrideEntry();
		entry.setColumnName("col1");
		entry.setIndexAnalyzer("myorg-INDEX_ANALYZER");
		entry.setSearchAnalyzer("myorg-SEARCH_ANALYZER");

		ColumnAnalyzerOverride override = new ColumnAnalyzerOverride();
		override.setOverrides(Arrays.asList(entry));

		// Columns with different types to exercise default qualified name resolution
		ColumnModel stringCol = new ColumnModel();
		stringCol.setId("1");
		stringCol.setColumnType(ColumnType.STRING);

		ColumnModel linkCol = new ColumnModel();
		linkCol.setId("2");
		linkCol.setColumnType(ColumnType.LINK);

		ColumnModel jsonCol = new ColumnModel();
		jsonCol.setId("3");
		jsonCol.setColumnType(ColumnType.JSON);

		List<ColumnModel> columns = Arrays.asList(stringCol, linkCol, jsonCol);
		List<ColumnAnalyzerOverride> overrides = Arrays.asList(override);

		// Expected: all qualified names collected into a single batch call
		Map<String, TextAnalyzer> batchResult = new HashMap<>();
		batchResult.put("myorg-CUSTOM_DEFAULT", new TextAnalyzer().setName("CUSTOM_DEFAULT"));
		batchResult.put("myorg-INDEX_ANALYZER", new TextAnalyzer().setName("INDEX_ANALYZER"));
		batchResult.put("myorg-SEARCH_ANALYZER", new TextAnalyzer().setName("SEARCH_ANALYZER"));
		String scientificQN = ColumnTypeToOpenSearchMapping.getDefaultAnalyzerQualifiedName(ColumnType.STRING);
		String keywordQN = ColumnTypeToOpenSearchMapping.getDefaultAnalyzerQualifiedName(ColumnType.LINK);
		String standardQN = ColumnTypeToOpenSearchMapping.getDefaultAnalyzerQualifiedName(ColumnType.JSON);
		batchResult.put(scientificQN, new TextAnalyzer().setName("SCIENTIFIC"));
		batchResult.put(keywordQN, new TextAnalyzer().setName("KEYWORD"));
		batchResult.put(standardQN, new TextAnalyzer().setName("STANDARD"));

		when(textAnalyzerDao.getByQualifiedNames(any())).thenReturn(batchResult);

		// call under test
		Map<String, TextAnalyzer> result = worker.collectAndLoadAnalyzers(config, overrides, columns);

		// Verify single batch call was made
		verify(textAnalyzerDao).getByQualifiedNames(argThat(names ->
				names.contains("myorg-CUSTOM_DEFAULT")
				&& names.contains("myorg-INDEX_ANALYZER")
				&& names.contains("myorg-SEARCH_ANALYZER")
				&& names.contains(scientificQN)
				&& names.contains(keywordQN)
				&& names.contains(standardQN)
		));

		// Verify all analyzers are in the result
		assertEquals(6, result.size());
		assertEquals("CUSTOM_DEFAULT", result.get("myorg-CUSTOM_DEFAULT").getName());
		assertEquals("SCIENTIFIC", result.get(scientificQN).getName());
		assertEquals("KEYWORD", result.get(keywordQN).getName());
		assertEquals("STANDARD", result.get(standardQN).getName());
	}

	@Test
	void testCollectAndLoadAnalyzersWithNullConfig() {
		ColumnModel col = new ColumnModel();
		col.setId("1");
		col.setColumnType(ColumnType.STRING);

		when(textAnalyzerDao.getByQualifiedNames(any())).thenReturn(new HashMap<>());

		// call under test
		Map<String, TextAnalyzer> result = worker.collectAndLoadAnalyzers(null, null, Arrays.asList(col));

		// Should still request the SCIENTIFIC default (always needed) + STRING column default
		verify(textAnalyzerDao).getByQualifiedNames(argThat(names ->
				names.contains(ColumnTypeToOpenSearchMapping.getDefaultAnalyzerQualifiedName(ColumnType.STRING))
		));
	}

	private void setupBuildMocks(String entityId) throws Exception {
		UserInfo adminUser = new UserInfo(true);
		when(userManager.getUserInfo(
				AuthorizationConstants.BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId()))
				.thenReturn(adminUser);

		SearchIndex searchIndex = new SearchIndex();
		searchIndex.setId(entityId);
		searchIndex.setDefiningSQL("SELECT * FROM syn789");
		searchIndex.setParentId("syn100");
		when(entityManager.getEntity(adminUser, entityId, SearchIndex.class)).thenReturn(searchIndex);

		// Source table is available
		when(tableManagerSupport.getTableStatusState(IdAndVersion.parse("syn789")))
				.thenReturn(Optional.of(TableState.AVAILABLE));

		when(searchConfigurationResolver.resolve(adminUser, null, "syn100"))
				.thenReturn(Optional.empty());

		UserInfo anonUser = new UserInfo(false);
		when(userManager.getUserInfo(
				AuthorizationConstants.BOOTSTRAP_PRINCIPAL.ANONYMOUS_USER.getPrincipalId()))
				.thenReturn(anonUser);

		QueryResultBundle countResult = new QueryResultBundle();
		countResult.setQueryCount(100L);
		when(tableQueryManager.querySinglePage(any(), any(), any(), any()))
				.thenReturn(countResult);

		lenient().when(tableQueryManager.runQueryAsStream(any(), any(), any(), any(), any()))
				.thenReturn(null);
	}
}
