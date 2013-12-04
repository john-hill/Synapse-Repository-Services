package org.sagebionetworks;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.http.HttpResponse;
import org.apache.http.util.EntityUtils;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.sagebionetworks.client.HttpClientProvider;
import org.sagebionetworks.client.HttpClientProviderImpl;
import org.sagebionetworks.client.SynapseClientImpl;
import org.sagebionetworks.repo.model.ACCESS_TYPE;
import org.sagebionetworks.repo.model.AccessControlList;
import org.sagebionetworks.repo.model.LocationData;
import org.sagebionetworks.repo.model.LocationTypeNames;
import org.sagebionetworks.repo.model.Project;
import org.sagebionetworks.repo.model.ResourceAccess;
import org.sagebionetworks.repo.model.Study;
import org.sagebionetworks.repo.model.Principal;

public class IT960TermsOfUse {
	private static SynapseClientImpl synapse = null;
	private static SynapseClientImpl adminSynapse = null;
	private static String authEndpoint = null;
	private static String repoEndpoint = null;
	
	private static Project project;
	private static Study dataset;

	@BeforeClass
	public static void beforeClass() throws Exception {

		authEndpoint = StackConfiguration.getAuthenticationServicePrivateEndpoint();
		repoEndpoint = StackConfiguration.getRepositoryServiceEndpoint();
		synapse = new SynapseClientImpl();
		synapse.setAuthEndpoint(authEndpoint);
		synapse.setRepositoryEndpoint(repoEndpoint);
		synapse.login(StackConfiguration.getIntegrationTestUserThreeName(),
				StackConfiguration.getIntegrationTestUserThreePassword());
		
		adminSynapse = new SynapseClientImpl();
		adminSynapse.setAuthEndpoint(authEndpoint);
		adminSynapse.setRepositoryEndpoint(repoEndpoint);
		adminSynapse.login(StackConfiguration.getIntegrationTestUserAdminName(),
				StackConfiguration.getIntegrationTestUserAdminPassword());
		
		project = new Project();
		project.setName("foo");
		project = adminSynapse.createEntity(project);
		// make the project public readable
		Collection<Principal> groups = adminSynapse.getGroups(0,100).getResults();
		String publicGroupPrincipalId = null;
		for (Principal group : groups) {
			if (group.getPrincipalName().equals("PUBLIC")) 
				publicGroupPrincipalId = group.getId();
		}
		assertNotNull(publicGroupPrincipalId);
		AccessControlList acl = adminSynapse.getACL(project.getId());
		
		// Now add public-readable and push it back
		Set<ResourceAccess> resourceAccessSet = acl.getResourceAccess();
		Set<ACCESS_TYPE> accessTypes = new HashSet<ACCESS_TYPE>();
		accessTypes.add(ACCESS_TYPE.READ);
		
		ResourceAccess resourceAccess = new ResourceAccess();
		resourceAccess.setPrincipalId(Long.parseLong(publicGroupPrincipalId)); // add PUBLIC, READ access
		resourceAccess.setAccessType(accessTypes); // add PUBLIC, READ access
		resourceAccessSet.add(resourceAccess); // add it to the list
		adminSynapse.updateACL(acl); // push back to Synapse
		
		// a dataset added to the project will inherit its parent's permissions, i.e. will be public-readable
		dataset = new Study();
		dataset.setName("bar");
		dataset.setParentId(project.getId());
		List<LocationData> locations = new ArrayList<LocationData>();
		LocationData ld = new LocationData();
		ld.setPath("http://foobar.com");
		ld.setType(LocationTypeNames.external);
		locations.add(ld);
		dataset.setLocations(locations);
		dataset.setMd5("12345678123456781234567812345678");
		dataset = adminSynapse.createEntity(dataset);
	}
	
	// make sure that after the test suite is done running the user has signed the Terms of Use
	@AfterClass
	public static void afterClass() throws Exception {
		if (adminSynapse!=null && project!=null) adminSynapse.deleteAndPurgeEntity(project);
	}

	@Test
	public void testGetTermsOfUse() throws Exception {
		HttpClientProvider clientProvider = new HttpClientProviderImpl();
		String requestUrl = authEndpoint+"/termsOfUse.html";
		String requestMethod = "GET";
		HttpResponse response = clientProvider.performRequest(requestUrl, requestMethod, null, null);
		String responseBody = (null != response.getEntity()) ? EntityUtils
				.toString(response.getEntity()) : null;
		assertTrue(responseBody.length()>100);
	}
	
	@Test
	public void testRepoSvcWithTermsOfUse() throws Exception {
		// should be able to see locations (i.e. the location is 'tier 1' data
		Study ds = synapse.getEntity(dataset.getId(), Study.class);
		List<LocationData> locations = ds.getLocations();
		assertTrue(locations!=null && locations.size()==1);
	}

	@Test
	public void testRepoSvcNoTermsOfUse() throws Exception {
		SynapseClientImpl anonymous = new SynapseClientImpl();
		anonymous.setAuthEndpoint(authEndpoint);
		anonymous.setRepositoryEndpoint(repoEndpoint);
		
		Study ds = synapse.getEntity(dataset.getId(), Study.class);
		List<LocationData> locations = ds.getLocations();
		assertTrue(locations!=null && locations.size()==1);
		
		Study idHolder = new Study();
		idHolder.setId(ds.getId());
		// an admin should be able to retreive the entity, including the locations
		ds = adminSynapse.getEntity(idHolder.getId(), Study.class);
		
		assertEquals("bar", ds.getName());
		locations = ds.getLocations();
		assertTrue(locations!=null && locations.size()==1);

	}
	

}
