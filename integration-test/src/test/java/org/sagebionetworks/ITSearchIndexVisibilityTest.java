package org.sagebionetworks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.client.SynapseAdminClient;
import org.sagebionetworks.client.SynapseClient;
import org.sagebionetworks.client.SynapseClientImpl;
import org.sagebionetworks.client.exceptions.SynapseException;
import org.sagebionetworks.repo.model.ACCESS_TYPE;
import org.sagebionetworks.repo.model.AccessControlList;
import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.Entity;
import org.sagebionetworks.repo.model.FileEntity;
import org.sagebionetworks.repo.model.Project;
import org.sagebionetworks.repo.model.ResourceAccess;
import org.sagebionetworks.repo.model.file.ExternalFileHandle;
import org.sagebionetworks.repo.model.search.SearchQuery;
import org.sagebionetworks.repo.model.search.SearchQueryResults;
import org.sagebionetworks.repo.model.search.SearchQueryType;
import org.sagebionetworks.repo.model.search.table.SearchIndex;
import org.sagebionetworks.repo.model.search.table.SearchIndexState;
import org.sagebionetworks.repo.model.search.table.SearchIndexStatus;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.repo.model.table.EntityView;
import org.sagebionetworks.repo.model.table.QueryResultBundle;
import org.sagebionetworks.repo.model.table.ViewTypeMask;

/**
 * Integration test verifying that a search index built from an entity view
 * only contains rows visible to the anonymous user. Entities with local
 * sharing settings that restrict access should be excluded from the index.
 */
@ExtendWith(ITTestExtension.class)
public class ITSearchIndexVisibilityTest {

	private static final long MAX_WAIT_MS = 1000 * 60 * 5;
	private static final long POLL_INTERVAL_MS = 2000;
	private static final long MAX_QUERY_TIMEOUT_MS = 1000 * 60 * 2;

	private static final String SAGE_TEAM_ID = AuthorizationConstants.BOOTSTRAP_PRINCIPAL.SAGE_BIONETWORKS
			.getPrincipalId().toString();

	private final SynapseAdminClient adminSynapse;
	private final SynapseClient synapse;
	private final List<Entity> entitiesToDelete = new ArrayList<>();
	private String userId;

	public ITSearchIndexVisibilityTest(SynapseAdminClient adminSynapse, SynapseClient synapse) {
		this.adminSynapse = adminSynapse;
		this.synapse = synapse;
	}

	@BeforeEach
	public void before() throws SynapseException {
		adminSynapse.clearAllLocks();
		userId = synapse.getMyProfile().getOwnerId();
		adminSynapse.addTeamMember(SAGE_TEAM_ID, userId, null, null);
	}

	@AfterEach
	public void after() {
		try {
			adminSynapse.removeTeamMember(SAGE_TEAM_ID, userId);
		} catch (SynapseException e) {
			// ignore
		}
		for (int i = entitiesToDelete.size() - 1; i >= 0; i--) {
			try {
				adminSynapse.deleteEntity(entitiesToDelete.get(i), true);
			} catch (SynapseException e) {
				// ignore
			}
		}
	}

	@Test
	public void testSearchIndexWithMixedVisibilityRows() throws Exception {
		// 1. Create a public project
		Project project = new Project();
		project.setName("ITSearchVisibility_" + UUID.randomUUID());
		project = synapse.createEntity(project);
		entitiesToDelete.add(project);

		// Grant PUBLIC_GROUP read access to the project
		grantPublicRead(project.getId());

		// 2. Create a column model for the view
		ColumnModel nameCol = new ColumnModel();
		nameCol.setName("name");
		nameCol.setColumnType(ColumnType.STRING);
		nameCol.setMaximumSize(100L);
		nameCol = synapse.createColumnModel(nameCol);

		// 3. Create two files in the project
		FileEntity publicFile = createFileEntity(project.getId(), "Public Alzheimer Study");
		entitiesToDelete.add(publicFile);

		FileEntity privateFile = createFileEntity(project.getId(), "Restricted Patient Data");
		entitiesToDelete.add(privateFile);

		// 4. Create local sharing settings on the private file — restrict to creator only
		AccessControlList privateAcl = new AccessControlList();
		privateAcl.setId(privateFile.getId());
		Set<ResourceAccess> privateAccess = new HashSet<>();
		ResourceAccess ownerAccess = new ResourceAccess();
		ownerAccess.setPrincipalId(Long.parseLong(userId));
		ownerAccess.setAccessType(new HashSet<>(Arrays.asList(
				ACCESS_TYPE.READ, ACCESS_TYPE.UPDATE, ACCESS_TYPE.DELETE,
				ACCESS_TYPE.CHANGE_PERMISSIONS, ACCESS_TYPE.DOWNLOAD)));
		privateAccess.add(ownerAccess);
		privateAcl.setResourceAccess(privateAccess);
		synapse.createACL(privateAcl);

		// 5. Create entity view scoped to the project
		EntityView view = new EntityView();
		view.setName("MixedVisibilityView_" + UUID.randomUUID());
		view.setParentId(project.getId());
		view.setViewTypeMask(ViewTypeMask.File.getMask());
		view.setScopeIds(Arrays.asList(project.getId()));
		view.setColumnIds(Arrays.asList(nameCol.getId()));
		view = synapse.createEntity(view);
		entitiesToDelete.add(view);

		// 6. Wait for the view to be queryable — the authenticated user should see both rows
		String viewSql = "SELECT * FROM " + view.getId();
		AsyncJobHelper.assertQueryBundleResults(synapse, view.getId(), viewSql, null, null,
				SynapseClient.COUNT_PARTMASK, (QueryResultBundle result) -> {
					assertNotNull(result.getQueryCount());
					assertEquals(2L, result.getQueryCount(),
							"Authenticated user should see both files in the view");
				}, MAX_QUERY_TIMEOUT_MS);

		// 7. Create a SearchIndex on the view
		SearchIndex searchIndex = new SearchIndex();
		searchIndex.setName("VisibilityTestIndex_" + UUID.randomUUID());
		searchIndex.setParentId(project.getId());
		searchIndex.setDefiningSQL(viewSql);
		searchIndex = synapse.createEntity(searchIndex);
		entitiesToDelete.add(searchIndex);

		// 8. Wait for the search index to become ACTIVE
		SearchIndexStatus status = waitForState(searchIndex.getId(), SearchIndexState.ACTIVE);
		assertEquals(SearchIndexState.ACTIVE, status.getState());
		assertNotNull(status.getAppliedConfiguration());

		// 9. Query the search index with MATCH_ALL — should only return the public file
		// The index was built using the anonymous user, so private rows should be excluded.
		SearchQuery query = new SearchQuery();
		query.setQueryType(SearchQueryType.MATCH_ALL);
		query.setLimit(10L);
		query.setOffset(0L);

		// TODO: Uncomment when query endpoints are implemented
		// SearchQueryResults results = synapse.searchIndex(searchIndex.getId(), query);
		// assertNotNull(results);
		// assertEquals(1L, results.getTotalHits(),
		//         "Search index should only contain the public file, not the private one");
		// assertEquals(1, results.getHits().size());
		// assertTrue(results.getHits().get(0).getFields().stream()
		//         .anyMatch(f -> "Public Alzheimer Study".equals(f.getValue())),
		//         "The indexed row should be the public file");
	}

	private void grantPublicRead(String entityId) throws SynapseException {
		AccessControlList acl = synapse.getACL(entityId);
		ResourceAccess publicAccess = new ResourceAccess();
		publicAccess.setPrincipalId(
				AuthorizationConstants.BOOTSTRAP_PRINCIPAL.PUBLIC_GROUP.getPrincipalId());
		publicAccess.setAccessType(new HashSet<>(Arrays.asList(ACCESS_TYPE.READ)));
		acl.getResourceAccess().add(publicAccess);
		synapse.updateACL(acl);
	}

	private FileEntity createFileEntity(String parentId, String name) throws SynapseException {
		ExternalFileHandle efh = new ExternalFileHandle();
		efh.setExternalURL("https://example.com/" + UUID.randomUUID());
		efh.setFileName(name + ".txt");
		efh = synapse.createExternalFileHandle(efh);

		FileEntity file = new FileEntity();
		file.setName(name);
		file.setParentId(parentId);
		file.setDataFileHandleId(efh.getId());
		return synapse.createEntity(file);
	}

	private SearchIndexStatus waitForState(String searchIndexId, SearchIndexState desiredState) throws Exception {
		long start = System.currentTimeMillis();
		SearchIndexStatus status = null;
		while (System.currentTimeMillis() - start < MAX_WAIT_MS) {
			status = adminSynapse.getSearchIndexStatus(searchIndexId);
			if (status.getState() != null && status.getState() == desiredState) {
				return status;
			}
			if (status.getState() == SearchIndexState.FAILED && desiredState != SearchIndexState.FAILED) {
				throw new AssertionError("Index build failed unexpectedly: " + status.getErrorMessage());
			}
			Thread.sleep(POLL_INTERVAL_MS);
		}
		throw new AssertionError("Timed out waiting for state " + desiredState
				+ ". Last status: " + (status != null ? status.getState() : "null"));
	}
}
