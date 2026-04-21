package org.sagebionetworks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.client.SynapseAdminClient;
import org.sagebionetworks.client.SynapseClient;
import org.sagebionetworks.client.exceptions.SynapseException;
import org.sagebionetworks.repo.model.ACCESS_TYPE;
import org.sagebionetworks.repo.model.AccessControlList;
import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.Entity;
import org.sagebionetworks.repo.model.Project;
import org.sagebionetworks.repo.model.ResourceAccess;
import org.sagebionetworks.repo.model.search.table.SearchIndex;
import org.sagebionetworks.repo.model.search.table.SearchIndexState;
import org.sagebionetworks.repo.model.search.table.SearchIndexStatus;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.repo.model.table.QueryResultBundle;
import org.sagebionetworks.repo.model.table.Row;
import org.sagebionetworks.repo.model.table.RowSet;
import org.sagebionetworks.repo.model.table.TableEntity;
import org.sagebionetworks.table.cluster.utils.TableModelUtils;

/**
 * Integration test for the SearchIndex status endpoint and lifecycle.
 * Verifies that the lifecycle worker builds the AOSS index and the
 * /entity/{id}/search/status endpoint returns the correct state transitions.
 */
@ExtendWith(ITTestExtension.class)
public class ITSearchIndexStatusTest {

	private static final long MAX_WAIT_MS = 1000 * 60 * 5;
	private static final long POLL_INTERVAL_MS = 2000;
	private static final long MAX_APPEND_TIMEOUT = 30 * 1000;

	private final SynapseAdminClient adminSynapse;
	private final SynapseClient synapse;
	private final List<Entity> entitiesToDelete = new ArrayList<>();

	private static final String SAGE_TEAM_ID = AuthorizationConstants.BOOTSTRAP_PRINCIPAL.SAGE_BIONETWORKS
			.getPrincipalId().toString();

	public ITSearchIndexStatusTest(SynapseAdminClient adminSynapse, SynapseClient synapse) {
		this.adminSynapse = adminSynapse;
		this.synapse = synapse;
	}

	@BeforeEach
	public void before() throws SynapseException {
		adminSynapse.clearAllLocks();
		String userId = synapse.getMyProfile().getOwnerId();
		adminSynapse.addTeamMember(SAGE_TEAM_ID, userId, null, null);
	}

	@AfterEach
	public void after() {
		try {
			adminSynapse.removeTeamMember(SAGE_TEAM_ID, synapse.getMyProfile().getOwnerId());
		} catch (SynapseException e) {
			// ignore
		}
		for (int i = entitiesToDelete.size() - 1; i >= 0; i--) {
			try {
				adminSynapse.deleteEntity(entitiesToDelete.get(i));
			} catch (SynapseException e) {
				// ignore
			}
		}
	}

	@Test
	public void testSearchIndexLifecycleWithStatusEndpoint() throws Exception {
		// 1. Create project
		Project project = new Project();
		project.setName("ITSearchIndexStatusTest_" + UUID.randomUUID());
		project = synapse.createEntity(project);
		entitiesToDelete.add(project);

		// Grant PUBLIC_GROUP read access — the lifecycle worker queries data as
		// the anonymous user, so the project must be publicly readable.
		grantPublicRead(project.getId());

		// 2. Create columns and table
		ColumnModel nameCol = new ColumnModel();
		nameCol.setName("studyName");
		nameCol.setColumnType(ColumnType.STRING);
		nameCol.setMaximumSize(100L);
		nameCol = synapse.createColumnModel(nameCol);

		TableEntity table = new TableEntity();
		table.setName("StatusTestTable");
		table.setParentId(project.getId());
		table.setColumnIds(Arrays.asList(nameCol.getId()));
		table = synapse.createEntity(table);
		entitiesToDelete.add(table);

		// 3. Populate with rows
		List<ColumnModel> columns = synapse.getColumnModelsForTableEntity(table.getId());
		RowSet rowSet = new RowSet();
		rowSet.setTableId(table.getId());
		rowSet.setHeaders(TableModelUtils.getSelectColumns(columns));
		rowSet.setRows(Arrays.asList(
			new Row().setValues(Arrays.asList("Alzheimer's Disease Study")),
			new Row().setValues(Arrays.asList("Cancer Genomics Atlas"))
		));
		synapse.appendRowsToTable(rowSet, MAX_APPEND_TIMEOUT, table.getId());

		// Wait for the table to be queryable before creating the SearchIndex.
		// This ensures entity replication is complete and ACLs are resolved,
		// so the lifecycle worker can read the SearchIndex entity when it
		// processes the CREATE change message.
		AsyncJobHelper.assertQueryBundleResults(synapse, table.getId(),
				"select * from " + table.getId(), null, null,
				SynapseClient.COUNT_PARTMASK,
				(QueryResultBundle result) -> {
					assertNotNull(result.getQueryCount());
					assertTrue(result.getQueryCount() > 0, "Table should have rows before SearchIndex build");
				}, MAX_WAIT_MS);

		// 4. Create SearchIndex entity (after replication is complete)
		SearchIndex searchIndex = new SearchIndex();
		searchIndex.setName("StatusTestSearchIndex");
		searchIndex.setParentId(project.getId());
		searchIndex.setDefiningSQL("select * from " + table.getId());
		searchIndex = synapse.createEntity(searchIndex);
		entitiesToDelete.add(searchIndex);

		// Wait briefly for entity replication to propagate the SearchIndex's
		// benefactor ACL, then trigger an update to generate a fresh change
		// message that the worker can process after replication is complete.
		Thread.sleep(5000);
		searchIndex = synapse.putEntity(searchIndex);

		// 5. Check initial status — may be null state (worker hasn't picked it up yet)
		//    or CREATING (worker started)
		// call under test
		SearchIndexStatus status = adminSynapse.getSearchIndexStatus(searchIndex.getId());
		assertNotNull(status);
		assertEquals(searchIndex.getId(), status.getSearchIndexId());

		// 6. Wait for the index to become ACTIVE by polling the status endpoint
		// call under test
		status = waitForState(searchIndex.getId(), SearchIndexState.ACTIVE);

		assertEquals(SearchIndexState.ACTIVE, status.getState());
		assertEquals(searchIndex.getId(), status.getSearchIndexId());
		assertNotNull(status.getChangedOn());
		assertNull(status.getErrorMessage());
		assertNotNull(status.getAppliedConfiguration(),
				"Expected appliedConfiguration to be set when index is ACTIVE");
		// The applied configuration should contain the mappings for our column
		assertTrue(status.getAppliedConfiguration().contains("mappings"),
				"Applied configuration should contain mappings");

		// 7. Delete the SearchIndex
		adminSynapse.deleteEntity(searchIndex, true);
		entitiesToDelete.remove(entitiesToDelete.size() - 1);
	}

	@Test
	public void testSearchIndexStatusWithInvalidDefiningSQL() throws Exception {
		// Create a SearchIndex with malformed SQL that cannot be parsed.
		// The lifecycle worker should set FAILED with an error message.
		Project project = new Project();
		project.setName("ITSearchIndexStatusFail_" + UUID.randomUUID());
		project = synapse.createEntity(project);
		entitiesToDelete.add(project);

		grantPublicRead(project.getId());

		// Create a real table so the source entity exists
		ColumnModel col = new ColumnModel();
		col.setName("name");
		col.setColumnType(ColumnType.STRING);
		col.setMaximumSize(50L);
		col = synapse.createColumnModel(col);

		TableEntity table = new TableEntity();
		table.setName("FailTestTable");
		table.setParentId(project.getId());
		table.setColumnIds(Arrays.asList(col.getId()));
		table = synapse.createEntity(table);
		entitiesToDelete.add(table);

		// Use a defining SQL with an invalid column that doesn't exist in the table
		SearchIndex searchIndex = new SearchIndex();
		searchIndex.setName("FailingSearchIndex");
		searchIndex.setParentId(project.getId());
		searchIndex.setDefiningSQL("select nonexistent_column_xyz from " + table.getId());
		searchIndex = synapse.createEntity(searchIndex);
		entitiesToDelete.add(searchIndex);

		// call under test — wait for the worker to set FAILED state
		SearchIndexStatus status = waitForState(searchIndex.getId(), SearchIndexState.FAILED);

		assertEquals(SearchIndexState.FAILED, status.getState());
		assertNotNull(status.getErrorMessage(), "Expected an error message for a failed index build");
		assertNull(status.getAppliedConfiguration(),
				"Applied configuration should be null when index build failed");
		assertNotNull(status.getChangedOn());
	}

	/**
	 * Polls the status endpoint until the desired state is reached or timeout expires.
	 */
	private SearchIndexStatus waitForState(String searchIndexId, SearchIndexState desiredState) throws Exception {
		long start = System.currentTimeMillis();
		SearchIndexStatus status = null;
		while (System.currentTimeMillis() - start < MAX_WAIT_MS) {
			status = adminSynapse.getSearchIndexStatus(searchIndexId);
			if (status.getState() != null && status.getState() == desiredState) {
				return status;
			}
			// If FAILED and we weren't waiting for FAILED, break early
			if (status.getState() == SearchIndexState.FAILED && desiredState != SearchIndexState.FAILED) {
				throw new AssertionError("Index build failed unexpectedly: " + status.getErrorMessage());
			}
			Thread.sleep(POLL_INTERVAL_MS);
		}
		throw new AssertionError("Timed out waiting for state " + desiredState
				+ ". Last status: " + (status != null ? status.getState() : "null"));
	}

	private void grantPublicRead(String entityId) throws SynapseException {
		AccessControlList acl = synapse.getACL(entityId);
		// Grant PUBLIC_GROUP READ for entity visibility
		ResourceAccess publicAccess = new ResourceAccess();
		publicAccess.setPrincipalId(
				AuthorizationConstants.BOOTSTRAP_PRINCIPAL.PUBLIC_GROUP.getPrincipalId());
		publicAccess.setAccessType(new HashSet<>(Arrays.asList(ACCESS_TYPE.READ)));
		acl.getResourceAccess().add(publicAccess);
		// Grant AUTHENTICATED_USERS READ + DOWNLOAD for table data access
		// (tables require DOWNLOAD permission to query content)
		ResourceAccess authUsersAccess = new ResourceAccess();
		authUsersAccess.setPrincipalId(
				AuthorizationConstants.BOOTSTRAP_PRINCIPAL.AUTHENTICATED_USERS_GROUP.getPrincipalId());
		authUsersAccess.setAccessType(new HashSet<>(Arrays.asList(ACCESS_TYPE.READ, ACCESS_TYPE.DOWNLOAD)));
		acl.getResourceAccess().add(authUsersAccess);
		synapse.updateACL(acl);
	}
}
