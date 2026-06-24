package org.sagebionetworks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.client.SynapseAdminClient;
import org.sagebionetworks.client.SynapseClient;
import org.sagebionetworks.client.SynapseClientImpl;
import org.sagebionetworks.client.exceptions.SynapseException;
import org.sagebionetworks.client.exceptions.SynapseNotFoundException;
import org.sagebionetworks.repo.model.ACCESS_TYPE;
import org.sagebionetworks.repo.model.AccessControlList;
import org.sagebionetworks.repo.model.Entity;
import org.sagebionetworks.repo.model.Folder;
import org.sagebionetworks.repo.model.Project;
import org.sagebionetworks.repo.model.ResourceAccess;
import org.sagebionetworks.repo.model.TeamConstants;
import org.sagebionetworks.repo.model.search.table.BindSearchConfigToEntityRequest;
import org.sagebionetworks.repo.model.search.table.ColumnAnalyzerOverride;
import org.sagebionetworks.repo.model.search.table.ColumnAnalyzerOverrideEntry;
import org.sagebionetworks.repo.model.search.table.ListSearchConfigurationsRequest;
import org.sagebionetworks.repo.model.search.table.ListSearchConfigurationsResponse;
import org.sagebionetworks.repo.model.search.table.ListTextAnalyzersRequest;
import org.sagebionetworks.repo.model.search.table.ListTextAnalyzersResponse;
import org.sagebionetworks.repo.model.search.table.SearchConfigBinding;
import org.sagebionetworks.repo.model.search.table.SearchConfiguration;
import org.sagebionetworks.repo.model.search.table.TextAnalyzer;

@ExtendWith(ITTestExtension.class)
public class ITSearchConfigurationTest {

	private final SynapseAdminClient adminSynapse;

	public ITSearchConfigurationTest(SynapseAdminClient adminSynapse) {
		this.adminSynapse = adminSynapse;
	}

	@BeforeEach
	public void before() throws SynapseException {
		adminSynapse.clearAllLocks();
	}

	@Test
	public void testCRUDWithSearchConfiguration() throws SynapseException {
		// Get org name and a bootstrapped analyzer to use as default
		ListTextAnalyzersResponse analyzers = adminSynapse.listTextAnalyzers(new ListTextAnalyzersRequest());
		TextAnalyzer bootstrappedAnalyzer = analyzers.getResults().get(0);
		String orgName = bootstrappedAnalyzer.getOrganizationName();
		String defaultAnalyzerName = orgName + "-" + bootstrappedAnalyzer.getName();

		String uniqueSuffix = UUID.randomUUID().toString().replace("-", "");
		String overrideLocalName = "IT_CONFIG_OVERRIDE_" + uniqueSuffix;
		String configName = "IT_TEST_CONFIG_" + uniqueSuffix;

		// Create a column analyzer override to reference. Inside an
		// ColumnAnalyzerOverrideEntry the analyzer slot is a $ref to a TextAnalyzer.
		ColumnAnalyzerOverride createdOverride = adminSynapse.createColumnAnalyzerOverride(new ColumnAnalyzerOverride()
				.setName(overrideLocalName)
				.setOrganizationName(orgName)
				.setOverrides(Collections.singletonList(new ColumnAnalyzerOverrideEntry()
						.setColumnName("abstract")
						.setAnalyzer(ref(defaultAnalyzerName)))));
		String overrideName = orgName + "-" + createdOverride.getName();

		// CREATE — defaultAnalyzer points the index at its primary TextAnalyzer; overrides optional.
		SearchConfiguration toCreate = new SearchConfiguration()
				.setName(configName)
				.setDescription("Integration test search configuration")
				.setOrganizationName(orgName)
				.setDefaultAnalyzer(ref(defaultAnalyzerName))
				.setColumnAnalyzerOverrides(Arrays.asList(ref(overrideName)));

		// call under test
		SearchConfiguration created = adminSynapse.createSearchConfiguration(toCreate);
		assertNotNull(created.getId());
		assertNotNull(created.getEtag());
		assertEquals(configName, created.getName());
		assertEquals("Integration test search configuration", created.getDescription());
		assertRefEquals(defaultAnalyzerName, created.getDefaultAnalyzer());
		assertEquals(1, created.getColumnAnalyzerOverrides().size());
		assertRefEquals(overrideName, created.getColumnAnalyzerOverrides().get(0));

		// call under test — verify GET returns the same data
		SearchConfiguration fetched = adminSynapse.getSearchConfiguration(created.getId());
		assertEquals(created.getId(), fetched.getId());
		assertEquals(created.getEtag(), fetched.getEtag());
		assertRefEquals(defaultAnalyzerName, fetched.getDefaultAnalyzer());

		// call under test — UPDATE: change description, verify references survive
		fetched.setDescription("Updated description");
		SearchConfiguration updated = adminSynapse.updateSearchConfiguration(fetched);
		assertEquals("Updated description", updated.getDescription());
		assertNotEquals(created.getEtag(), updated.getEtag());
		assertRefEquals(defaultAnalyzerName, updated.getDefaultAnalyzer());
		assertEquals(1, updated.getColumnAnalyzerOverrides().size());
		assertRefEquals(overrideName, updated.getColumnAnalyzerOverrides().get(0));

		// call under test — UPDATE: clear optional references. An etag rotation alone
		// does not prove the cleared list actually persisted; assert on the data.
		updated.setColumnAnalyzerOverrides(null);
		SearchConfiguration cleared = adminSynapse.updateSearchConfiguration(updated);
		assertTrue(isNullOrEmpty(cleared.getColumnAnalyzerOverrides()),
				"columnAnalyzerOverrides should be cleared, was: " + cleared.getColumnAnalyzerOverrides());
		assertRefEquals(defaultAnalyzerName, cleared.getDefaultAnalyzer());

		// call under test — LIST by org
		ListSearchConfigurationsResponse listResponse = adminSynapse.listSearchConfigurations(
				new ListSearchConfigurationsRequest().setOrganizationName(orgName));
		assertNotNull(listResponse.getResults());
		assertTrue(listResponse.getResults().stream().anyMatch(c -> created.getId().equals(c.getId())));
	}

	@Test
	public void testCRUDWithInlineDefaultAnalyzer() throws SynapseException {
		// Inline form: defaultAnalyzer carries the bare OpenSearch settings.analysis block
		// directly (no $ref, no envelope). The wire path must round-trip the JSON through
		// controller → manager → DAO → re-fetch unchanged.
		ListTextAnalyzersResponse analyzers = adminSynapse.listTextAnalyzers(new ListTextAnalyzersRequest());
		String orgName = analyzers.getResults().get(0).getOrganizationName();

		JSONObject inlineDefault = new JSONObject().put(
				"analyzer", new JSONObject().put(
						"default", new JSONObject()
								.put("type", "custom")
								.put("tokenizer", "standard")
								.put("filter", new org.json.JSONArray().put("lowercase"))));

		String configName = "IT_INLINE_CONFIG_" + UUID.randomUUID().toString().replace("-", "");
		SearchConfiguration toCreate = new SearchConfiguration()
				.setName(configName)
				.setDescription("Inline default analyzer")
				.setOrganizationName(orgName)
				.setDefaultAnalyzer(inlineDefault);

		// call under test
		SearchConfiguration created = adminSynapse.createSearchConfiguration(toCreate);
		assertNotNull(created.getId());
		assertJsonEquals(inlineDefault, created.getDefaultAnalyzer());

		// call under test — GET round-trips the inline JSON unchanged
		SearchConfiguration fetched = adminSynapse.getSearchConfiguration(created.getId());
		assertJsonEquals(inlineDefault, fetched.getDefaultAnalyzer());
	}

	private static void assertJsonEquals(Object expected, Object actual) {
		com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
		try {
			assertEquals(mapper.readTree(String.valueOf(expected)),
					mapper.readTree(String.valueOf(actual)));
		} catch (java.io.IOException e) {
			throw new AssertionError(e);
		}
	}

	private static boolean isNullOrEmpty(List<?> list) {
		return list == null || list.isEmpty();
	}

	/** Build a {@code {"$ref": "{org}-{name}"}} reference value as a JSONObject. */
	private static JSONObject ref(String qualifiedName) {
		return new JSONObject().put("$ref", qualifiedName);
	}

	/**
	 * Assert that {@code actual} carries a {@code {"$ref": qname}} reference. The wire
	 * deserializer surfaces it as a {@code JSONObjectAdapter}, so we compare via the
	 * {@code $ref} value extracted by reflection of the JSON shape.
	 */
	private static void assertRefEquals(String expectedQname, Object actual) {
		assertNotNull(actual, "expected $ref to '" + expectedQname + "', got null");
		String json = String.valueOf(actual);
		try {
			JSONObject parsed = new JSONObject(json);
			assertEquals(1, parsed.length(), "expected single-key $ref object, got: " + json);
			assertEquals(expectedQname, parsed.optString("$ref"));
		} catch (org.json.JSONException e) {
			throw new AssertionError("expected $ref JSON object, got: " + json, e);
		}
	}

	@Test
	public void testBindAndUnbindSearchConfigToEntity() throws SynapseException {
		// Create a project to bind to
		Project project = new Project();
		project.setName("IT_BIND_TEST_PROJECT_" + UUID.randomUUID().toString().replace("-", ""));
		Entity createdProject = adminSynapse.createEntity(project);

		try {
			ListTextAnalyzersResponse analyzers = adminSynapse.listTextAnalyzers(new ListTextAnalyzersRequest());
			TextAnalyzer bootstrappedAnalyzer = analyzers.getResults().get(0);
			String orgName = bootstrappedAnalyzer.getOrganizationName();
			String defaultAnalyzerName = orgName + "-" + bootstrappedAnalyzer.getName();

			SearchConfiguration createdConfig = adminSynapse.createSearchConfiguration(new SearchConfiguration()
					.setName("IT_BIND_CONFIG_" + UUID.randomUUID().toString().replace("-", ""))
					.setOrganizationName(orgName)
					.setDefaultAnalyzer(ref(defaultAnalyzerName)));

			// call under test — BIND
			SearchConfigBinding binding = adminSynapse.bindSearchConfigToEntity(new BindSearchConfigToEntityRequest()
					.setEntityId(createdProject.getId())
					.setSearchConfigurationId(createdConfig.getId()));

			assertNotNull(binding.getBindId());
			assertEquals(createdConfig.getId(), binding.getSearchConfigurationId());
			assertEquals(createdProject.getId(), "syn" + binding.getObjectId());
			assertEquals("entity", binding.getObjectType());
			assertNotNull(binding.getCreatedOn());

			// call under test — GET binding
			SearchConfigBinding fetched = adminSynapse.getSearchConfigBindingForEntity(createdProject.getId());
			assertEquals(binding.getBindId(), fetched.getBindId());
			assertEquals(createdConfig.getId(), fetched.getSearchConfigurationId());

			// call under test — UNBIND
			adminSynapse.clearSearchConfigBindingForEntity(createdProject.getId());

			// Verify binding is gone
			assertThrows(SynapseNotFoundException.class, () ->
				adminSynapse.getSearchConfigBindingForEntity(createdProject.getId()));
		} finally {
			adminSynapse.deleteEntity(createdProject);
		}
	}

	@Test
	public void testBindAndUnbindWithBenefactorInheritedAcl() throws Exception {
		// Reproduces PLFM: a non-admin Sage employee who has UPDATE on a project via that
		// project's local ACL must be able to bind/unbind a config on a child folder that has
		// NO local ACL (it inherits the project's ACL). The authorization check must resolve the
		// benefactor, not read only the folder's own (absent) ACL.
		SynapseClient userClient = new SynapseClientImpl();
		Long userId = null;
		Entity createdProject = null;
		try {
			userId = SynapseClientHelper.createUser(adminSynapse, userClient);
			// The binding endpoint is gated to Sage Bionetworks employees.
			adminSynapse.addTeamMember(TeamConstants.SAGE_BIONETWORKS_TEAM_ID.toString(), userId.toString(),
					null, null);

			// Project P with a local ACL granting the user UPDATE (and READ).
			Project project = new Project();
			project.setName("IT_BENEFACTOR_BIND_PROJECT_" + UUID.randomUUID().toString().replace("-", ""));
			createdProject = adminSynapse.createEntity(project);

			AccessControlList acl = adminSynapse.getACL(createdProject.getId());
			ResourceAccess userAccess = new ResourceAccess();
			userAccess.setPrincipalId(userId);
			userAccess.setAccessType(EnumSet.of(ACCESS_TYPE.READ, ACCESS_TYPE.UPDATE));
			acl.getResourceAccess().add(userAccess);
			adminSynapse.updateACL(acl);

			// Child Folder E under P with NO local ACL — it inherits P's ACL.
			Folder folder = new Folder();
			folder.setParentId(createdProject.getId());
			Entity createdFolder = adminSynapse.createEntity(folder);

			// Confirm the folder inherits (has no local ACL of its own).
			assertThrows(SynapseNotFoundException.class, () -> adminSynapse.getACL(createdFolder.getId()));

			ListTextAnalyzersResponse analyzers = adminSynapse.listTextAnalyzers(new ListTextAnalyzersRequest());
			TextAnalyzer bootstrappedAnalyzer = analyzers.getResults().get(0);
			String orgName = bootstrappedAnalyzer.getOrganizationName();
			String defaultAnalyzerName = orgName + "-" + bootstrappedAnalyzer.getName();
			SearchConfiguration createdConfig = adminSynapse.createSearchConfiguration(new SearchConfiguration()
					.setName("IT_BENEFACTOR_BIND_CONFIG_" + UUID.randomUUID().toString().replace("-", ""))
					.setOrganizationName(orgName)
					.setDefaultAnalyzer(ref(defaultAnalyzerName)));

			// call under test — as the non-admin user, BIND to the inheriting folder.
			SearchConfigBinding binding = userClient.bindSearchConfigToEntity(new BindSearchConfigToEntityRequest()
					.setEntityId(createdFolder.getId())
					.setSearchConfigurationId(createdConfig.getId()));

			assertNotNull(binding.getBindId());
			assertEquals(createdConfig.getId(), binding.getSearchConfigurationId());
			assertEquals(createdFolder.getId(), "syn" + binding.getObjectId());

			// call under test — as the non-admin user, UNBIND.
			userClient.clearSearchConfigBindingForEntity(createdFolder.getId());
			assertThrows(SynapseNotFoundException.class, () ->
				adminSynapse.getSearchConfigBindingForEntity(createdFolder.getId()));
		} finally {
			if (createdProject != null) {
				adminSynapse.deleteEntity(createdProject);
			}
			if (userId != null) {
				adminSynapse.deleteUser(userId);
			}
		}
	}
}
