package org.sagebionetworks.search.workers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.sagebionetworks.repo.model.util.AccessControlListUtil.createResourceAccess;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.AsynchronousJobWorkerHelper;
import org.sagebionetworks.repo.manager.EntityManager;
import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.manager.search.TextAnalyzerBootstrap;
import org.sagebionetworks.repo.manager.table.ColumnModelManager;
import org.sagebionetworks.repo.model.ACCESS_TYPE;
import org.sagebionetworks.repo.model.AuthorizationConstants.BOOTSTRAP_PRINCIPAL;
import org.sagebionetworks.repo.model.Folder;
import org.sagebionetworks.repo.model.ObjectType;
import org.sagebionetworks.repo.model.Project;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.annotation.v2.Annotations;
import org.sagebionetworks.repo.model.annotation.v2.AnnotationsV2TestUtils;
import org.sagebionetworks.repo.model.annotation.v2.AnnotationsValueType;
import org.sagebionetworks.repo.model.auth.NewUser;
import org.sagebionetworks.repo.model.entity.IdAndVersion;
import org.sagebionetworks.repo.model.helper.AccessControlListObjectHelper;
import org.sagebionetworks.repo.model.jdo.KeyFactory;
import org.sagebionetworks.repo.model.search.SearchFieldValue;
import org.sagebionetworks.repo.model.search.SearchHit;
import org.sagebionetworks.repo.model.search.SearchQuery;
import org.sagebionetworks.repo.model.search.SearchQueryPart;
import org.sagebionetworks.repo.model.search.SearchQueryResults;
import org.sagebionetworks.repo.model.search.dsl.MatchAllQuery;
import org.sagebionetworks.repo.model.search.dsl.Query;
import org.sagebionetworks.repo.model.search.table.SearchIndex;
import org.sagebionetworks.repo.model.search.table.SearchIndexQuery;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.repo.model.table.EntityView;
import org.sagebionetworks.repo.model.table.MaterializedView;
import org.sagebionetworks.repo.model.table.ObjectField;
import org.sagebionetworks.repo.model.table.ViewTypeMask;
import org.sagebionetworks.repo.service.EntityService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Autowired integration test proving that a {@link SearchIndex} built over a materialized view of
 * two entity views applies row-level benefactor (ACL) filtering at query time: two users with
 * different access to the source rows see different result sets, even though both can read the
 * SearchIndex and its source views.
 *
 * <p>The build indexes every source row without authorization (read access is enforced only at
 * query time via the per-row {@code _benefactor_<i>} terms filters), so this is the end-to-end
 * proof that the query-side {@code SearchIndexQueryManager.buildBenefactorAccessFilters} +
 * {@code TableQueryManager.computeAccessibleBenefactors} gate actually restricts hits per user.
 *
 * <p>The view rows are folders (a folder is both an entity with a benefactor and a view row), which
 * keeps the fixture to the minimum needed to exercise per-row benefactor filtering with no file
 * handles.
 *
 * <p>Live against the Tomcat + MySQL + AOSS stack via {@code test-context.xml}.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = {"classpath:test-context.xml"})
public class SearchIndexBenefactorFilterWorkerAutowireTest {

	private static final long MAX_WAIT_MS = 5 * 60 * 1000; // 5 minutes

	@Autowired
	private EntityService entityService;
	@Autowired
	private EntityManager entityManager;
	@Autowired
	private UserManager userManager;
	@Autowired
	private ColumnModelManager columnModelManager;
	@Autowired
	private AsynchronousJobWorkerHelper asyncHelper;
	@Autowired
	private TextAnalyzerBootstrap textAnalyzerBootstrap;
	@Autowired
	private AccessControlListObjectHelper aclDaoHelper;

	private UserInfo adminUser;
	private UserInfo userA;
	private UserInfo userB;

	@BeforeEach
	public void before() {
		aclDaoHelper.truncateAll();
		// Re-seed defensively: the shared dev MySQL TEXT_ANALYZER table is truncated by other
		// modules' tests, and the workers context's bootstrap may have run before that truncate.
		textAnalyzerBootstrap.bootstrapSystemAnalyzers();
		adminUser = userManager.getUserInfo(BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId());

		String nameA = UUID.randomUUID().toString();
		userA = userManager.createOrGetTestUser(adminUser,
				new NewUser().setUserName(nameA).setEmail("employee@sagebase.org"));
		String nameB = UUID.randomUUID().toString();
		userB = userManager.createOrGetTestUser(adminUser,
				new NewUser().setUserName(nameB).setEmail("employee1@sagebase.org"));
	}

	@AfterEach
	public void after() {
		aclDaoHelper.truncateAll();
		entityManager.truncateAll();
	}

	/**
	 * Build a SearchIndex over an MV joining two entity views, then query it as two users with
	 * different ACLs. Each project holds two folders: an even-index folder that inherits the project
	 * benefactor and an odd-index folder with its own ACL (benefactor = that folder). Both users get
	 * READ on the two projects, the two views, and the MV — so both pass the recursive source
	 * read-access check and can query. Only {@code userA} additionally gets READ on the own-ACL
	 * folders, so the per-row {@code _benefactor} filters let userA see every row while userB sees
	 * only the project-benefactor row.
	 */
	@Test
	public void testSearchIndexBenefactorFilteringDiffersByUser() throws Exception {
		int foldersPerProject = 2;
		Hierarchy left = createProjectHierachy(foldersPerProject);
		Hierarchy right = createProjectHierachy(foldersPerProject);

		// Both users can read both projects (and therefore the folders that inherit the project
		// benefactor) and can read the views/MV built below.
		grantRead(left.project.getId(), userA, userB);
		grantRead(right.project.getId(), userA, userB);

		// Only userA can read the separately-benefactored folders on each side.
		left.ownAclFolderIds().forEach(id -> grantRead(id, userA));
		right.ownAclFolderIds().forEach(id -> grantRead(id, userA));

		IdAndVersion leftViewId = createFolderView(left);
		IdAndVersion rightViewId = createFolderView(right);

		// Join the two views on the shared annotation so each MV row carries two benefactor columns
		// (ROW_BENEFACTOR__A0 from left, ROW_BENEFACTOR__A1 from right). Alias the selected columns
		// to unprefixed names so the SearchIndex defining SQL can reference them as id / groupKey
		// (a join exposes columns under their table-alias-prefixed name otherwise, e.g. "l.id").
		String definingSql = String.format(
				"select l.id as id, l.groupKey as groupKey from %s l join %s r on (l.groupKey = r.groupKey)",
				leftViewId, rightViewId);
		// The MV is parented under the left project and has no ACL of its own, so it inherits the
		// project's benefactor — both users already have READ there. The two source views likewise
		// inherit their projects' ACLs, so both users pass the recursive source read-access check.
		MaterializedView mv = asyncHelper.createMaterializedView(adminUser, left.project.getId(), definingSql, false);
		IdAndVersion mvId = KeyFactory.idAndVersion(mv.getId(), null);

		// Wait for the MV to materialize before defining a SearchIndex over it.
		asyncHelper.assertQueryResult(adminUser, "select count(*) from " + mvId, (results) -> {
			assertNotNull(results.getQueryResult().getQueryResults().getRows());
		}, MAX_WAIT_MS);

		SearchIndex searchIndex = new SearchIndex();
		searchIndex.setName("BenefactorFilterSearchIndex_" + UUID.randomUUID());
		searchIndex.setParentId(left.project.getId());
		searchIndex.setDefiningSQL("select id, groupKey from " + mvId);
		searchIndex = entityService.createEntity(adminUser.getId(), searchIndex, null);

		SearchIndexQuery query = new SearchIndexQuery();
		query.setSearchIndexId(searchIndex.getId());
		query.setSearchQuery(new SearchQuery().setQuery(new Query().setMatch_all(new MatchAllQuery())).setSize(100L));
		query.setResponseParts(EnumSet.of(SearchQueryPart.HITS, SearchQueryPart.TOTAL_HITS));

		// userA reads every own-ACL folder, so every MV row is visible. The query worker retries
		// while the index is still CREATING and while AOSS catches up to the writes.
		Set<String> idsVisibleToA = new HashSet<>(left.folderIds());
		asyncHelper.assertJobResponse(userA, query, (SearchQueryResults results) -> {
			Set<String> ids = hitIds(results);
			assertEquals(idsVisibleToA.size(), ids.size());
			assertEquals(idsVisibleToA, ids);
		}, MAX_WAIT_MS, AsynchronousJobWorkerHelper.INFINITE_RETRIES);

		// userB lacks both own-ACL folders, so the rows whose left OR right benefactor is an own-ACL
		// folder are filtered out — userB sees a strict subset of what userA sees.
		Set<String> idsVisibleToB = idsVisibleToProjectOnly(left, right);
		assertTrue(idsVisibleToB.size() < idsVisibleToA.size(),
				"test fixture must yield fewer rows for the less-privileged user");
		assertTrue(idsVisibleToA.containsAll(idsVisibleToB));
		asyncHelper.assertJobResponse(userB, query, (SearchQueryResults results) -> {
			Set<String> ids = hitIds(results);
			assertEquals(idsVisibleToB.size(), ids.size());
			assertEquals(idsVisibleToB, ids);
		}, MAX_WAIT_MS, AsynchronousJobWorkerHelper.INFINITE_RETRIES);
	}

	/**
	 * The MV joins left and right on {@code groupKey}. Both hierarchies use the same groupKey layout
	 * (folder i has groupKey i), so the join is row-aligned: MV row i carries left folder i's
	 * benefactor as {@code __A0} and right folder i's as {@code __A1}. A user sees MV row i only if it
	 * can read BOTH side-i benefactors. userB can read only the project benefactor on each side, so it
	 * sees MV row i only when BOTH side-i folders inherit the project benefactor. createProjectHierachy
	 * gives folder i the project benefactor when i is even, so the row-aligned join keeps exactly the
	 * even-index folders.
	 */
	private Set<String> idsVisibleToProjectOnly(Hierarchy left, Hierarchy right) {
		Set<String> visible = new HashSet<>();
		for (int i = 0; i < left.folders.size(); i++) {
			if (left.isProjectBenefactor(i) && right.isProjectBenefactor(i)) {
				visible.add(left.folders.get(i).getId());
			}
		}
		return visible;
	}

	private void grantRead(String entityId, UserInfo... users) {
		aclDaoHelper.update(entityId, ObjectType.ENTITY, acl -> {
			for (UserInfo user : users) {
				acl.getResourceAccess().add(createResourceAccess(user.getId(), ACCESS_TYPE.READ));
			}
		});
	}

	private static Set<String> hitIds(SearchQueryResults results) {
		assertNotNull(results.getHits());
		return results.getHits().stream().map(h -> fieldValue(h, "id")).collect(Collectors.toSet());
	}

	private static String fieldValue(SearchHit hit, String fieldName) {
		if (hit.getFields() == null) {
			return null;
		}
		for (SearchFieldValue fv : hit.getFields()) {
			if (fieldName.equals(fv.getName())) {
				return fv.getValue();
			}
		}
		return null;
	}

	/**
	 * Create a Folder-scoped entity view over the hierarchy's project, schema = id + groupKey.
	 * Annotates each folder with its index as groupKey so the two views join row-for-row, and waits
	 * for replication.
	 */
	private IdAndVersion createFolderView(Hierarchy h) throws Exception {
		List<ColumnModel> schema = Arrays.asList(
				new ColumnModel().setName(ObjectField.id.name()).setColumnType(ColumnType.ENTITYID),
				new ColumnModel().setName("groupKey").setColumnType(ColumnType.INTEGER));
		schema = columnModelManager.createColumnModels(adminUser, schema);

		for (int i = 0; i < h.folders.size(); i++) {
			Folder folder = h.folders.get(i);
			Annotations annos = entityManager.getAnnotations(adminUser, folder.getId());
			AnnotationsV2TestUtils.putAnnotations(annos, "groupKey", Long.toString(i), AnnotationsValueType.LONG);
			entityManager.updateAnnotations(adminUser, folder.getId(), annos);
			asyncHelper.waitForEntityReplication(adminUser, folder.getId(), MAX_WAIT_MS);
		}

		List<String> columnIds = schema.stream().map(ColumnModel::getId).collect(Collectors.toList());
		EntityView view = asyncHelper.createEntityView(adminUser, UUID.randomUUID().toString(), h.project.getId(),
				columnIds, Arrays.asList(h.project.getId()), ViewTypeMask.Folder.getMask(), false);

		// Wait for the view to build so the MV over it can materialize.
		asyncHelper.assertQueryResult(adminUser, "select count(*) from " + view.getId(), (results) -> {
			assertNotNull(results.getQueryResult().getQueryResults().getRows());
		}, MAX_WAIT_MS);
		return KeyFactory.idAndVersion(view.getId(), null);
	}

	/**
	 * A project with {@code numberOfFolders} folders that serve as the view rows. Even-index folders
	 * inherit the project benefactor; odd-index folders get their own ACL, so their benefactor is the
	 * folder itself.
	 */
	private Hierarchy createProjectHierachy(int numberOfFolders) {
		Project project = entityManager.getEntity(adminUser,
				entityManager.createEntity(adminUser, new Project().setName(UUID.randomUUID().toString()), null),
				Project.class);

		List<Folder> folders = new ArrayList<>(numberOfFolders);
		for (int i = 0; i < numberOfFolders; i++) {
			Folder folder = entityManager.getEntity(adminUser, entityManager.createEntity(adminUser,
					new Folder().setName("folder_" + i).setParentId(project.getId()), null), Folder.class);
			if (i % 2 != 0) {
				// An own ACL (admin only for now) makes the folder its own benefactor rather than
				// inheriting the project. Per-user READ is granted by the test as needed.
				aclDaoHelper.create(a -> {
					a.setId(folder.getId());
					a.getResourceAccess().add(createResourceAccess(adminUser.getId(), ACCESS_TYPE.CHANGE_PERMISSIONS));
					a.getResourceAccess().add(createResourceAccess(adminUser.getId(), ACCESS_TYPE.READ));
				});
			}
			folders.add(folder);
		}
		return new Hierarchy(project, folders);
	}

	private static class Hierarchy {
		final Project project;
		final List<Folder> folders;

		Hierarchy(Project project, List<Folder> folders) {
			this.project = project;
			this.folders = folders;
		}

		List<String> folderIds() {
			return folders.stream().map(Folder::getId).collect(Collectors.toList());
		}

		List<String> ownAclFolderIds() {
			List<String> ids = new ArrayList<>();
			for (int i = 0; i < folders.size(); i++) {
				if (!isProjectBenefactor(i)) {
					ids.add(folders.get(i).getId());
				}
			}
			return ids;
		}

		boolean isProjectBenefactor(int i) {
			return i % 2 == 0;
		}
	}
}
