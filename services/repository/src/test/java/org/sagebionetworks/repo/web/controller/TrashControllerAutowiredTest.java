package org.sagebionetworks.repo.web.controller;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import javax.servlet.http.HttpServlet;

import junit.framework.Assert;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.Entity;
import org.sagebionetworks.repo.model.EntityHeader;
import org.sagebionetworks.repo.model.PaginatedResults;
import org.sagebionetworks.repo.model.Principal;
import org.sagebionetworks.repo.model.Project;
import org.sagebionetworks.repo.model.Study;
import org.sagebionetworks.repo.model.TrashedEntity;
import org.sagebionetworks.repo.model.auth.NewUser;
import org.sagebionetworks.repo.web.UrlHelpers;
import org.sagebionetworks.repo.web.service.EntityService;
import org.sagebionetworks.schema.adapter.JSONObjectAdapter;
import org.sagebionetworks.schema.adapter.org.json.JSONObjectAdapterImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = { "classpath:test-context.xml" })
public class TrashControllerAutowiredTest {

	@Autowired
	private EntityService entityService;
	
	@Autowired
	private UserManager userManager;

	private final Long adminId = AuthorizationConstants.ADMIN_USER_ID;
	private Long testId;
	private Entity parent;
	private Entity child;

	@Before
	public void before() throws Exception {
		// Create a non-admin user
		NewUser nu = new NewUser();
		nu.setEmail(UUID.randomUUID().toString()+"@test.com");
		nu.setPrincipalName(UUID.randomUUID().toString());
		Principal newPrincipal = userManager.createUser(nu);
		testId = Long.parseLong(newPrincipal.getId());
		
		Assert.assertNotNull(this.entityService);
		parent = new Project();
		parent.setName("TrashControllerAutowiredTest.parent");
		HttpServlet dispatchServlet = DispatchServletSingleton.getInstance();
		parent = ServletTestHelper.createEntity(dispatchServlet, parent, testId);
		Assert.assertNotNull(parent);
		child = new Study();
		child.setName("TrashControllerAutowiredTest.child");
		child.setParentId(parent.getId());
		child.setEntityType(Study.class.getName());
		child = ServletTestHelper.createEntity(dispatchServlet, child, testId);
		Assert.assertNotNull(child);
		Assert.assertEquals(parent.getId(), child.getParentId());
		EntityHeader benefactor = entityService.getEntityBenefactor(child.getId(), testId, null);
		Assert.assertEquals(parent.getId(), benefactor.getId());
	}

	@After
	public void after() throws Exception {
		if (child != null) {
			entityService.deleteEntity(testId, child.getId());
		}
		if (parent != null) {
			entityService.deleteEntity(testId, parent.getId());
		}
		// Delete the test user
		userManager.delete(testId.toString());
	}

	@Test
	public void testPurge() throws Exception {

		// The trash can may not be empty before we put anything there
		// So we get base numbers first
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setMethod("GET");
		request.addHeader("Accept", "application/json");
		request.setRequestURI(UrlHelpers.TRASHCAN_VIEW);
		request.setParameter(AuthorizationConstants.USER_ID_PARAM, testId.toString());
		MockHttpServletResponse response = new MockHttpServletResponse();
		HttpServlet servlet = DispatchServletSingleton.getInstance();
		servlet.service(request, response);
		Assert.assertEquals(200, response.getStatus());
		String jsonStr = response.getContentAsString();
		JSONObjectAdapter adapter = new JSONObjectAdapterImpl(jsonStr);
		PaginatedResults<TrashedEntity> results = new PaginatedResults<TrashedEntity>(TrashedEntity.class);
		results.initializeFromJSONObject(adapter);
		long baseTotal = results.getTotalNumberOfResults();
		long baseCount = results.getResults().size();

		// Move the parent to the trash can
		request = new MockHttpServletRequest();
		request.setMethod("PUT");
		request.addHeader("Accept", "application/json");
		request.setRequestURI(UrlHelpers.TRASHCAN + "/trash/" + parent.getId());
		request.setParameter(AuthorizationConstants.USER_ID_PARAM, testId.toString());
		response = new MockHttpServletResponse();
		servlet = DispatchServletSingleton.getInstance();
		servlet.service(request, response);
		Assert.assertEquals(200, response.getStatus());

		// Purge the parent
		request = new MockHttpServletRequest();
		request.setMethod("PUT");
		request.addHeader("Accept", "application/json");
		request.setRequestURI(UrlHelpers.TRASHCAN_PURGE + "/" + parent.getId());
		request.setParameter(AuthorizationConstants.USER_ID_PARAM, testId.toString());
		response = new MockHttpServletResponse();
		servlet = DispatchServletSingleton.getInstance();
		servlet.service(request, response);
		Assert.assertEquals(200, response.getStatus());

		// Both the parent and the child should be gone
		request = new MockHttpServletRequest();
		request.setMethod("GET");
		request.addHeader("Accept", "application/json");
		request.setRequestURI(UrlHelpers.ENTITY + "/" + parent.getId());
		request.setParameter(AuthorizationConstants.USER_ID_PARAM, testId.toString());
		response = new MockHttpServletResponse();
		servlet = DispatchServletSingleton.getInstance();
		servlet.service(request, response);
		Assert.assertEquals(404, response.getStatus());

		request = new MockHttpServletRequest();
		request.setMethod("GET");
		request.addHeader("Accept", "application/json");
		request.setRequestURI(UrlHelpers.ENTITY + "/" + child.getId());
		request.setParameter(AuthorizationConstants.USER_ID_PARAM, testId.toString());
		response = new MockHttpServletResponse();
		servlet = DispatchServletSingleton.getInstance();
		servlet.service(request, response);
		Assert.assertEquals(404, response.getStatus());

		// The trash can should be empty
		request = new MockHttpServletRequest();
		request.setMethod("GET");
		request.addHeader("Accept", "application/json");
		request.setRequestURI(UrlHelpers.TRASHCAN_VIEW);
		request.setParameter(AuthorizationConstants.USER_ID_PARAM, testId.toString());
		response = new MockHttpServletResponse();
		servlet = DispatchServletSingleton.getInstance();
		servlet.service(request, response);
		Assert.assertEquals(200, response.getStatus());
		jsonStr = response.getContentAsString();
		adapter = new JSONObjectAdapterImpl(jsonStr);
		results = new PaginatedResults<TrashedEntity>(TrashedEntity.class);
		results.initializeFromJSONObject(adapter);
		Assert.assertEquals(baseTotal, results.getTotalNumberOfResults());
		Assert.assertEquals(baseCount, results.getResults().size());
		for (TrashedEntity trash : results.getResults()) {
			if (parent.getId().equals(trash.getEntityId())
					|| child.getId().equals(trash.getEntityId())) {
				Assert.fail();
			}
		}

		// Already purged, no need to clean
		child = null;
		parent = null;
	}

	@Test
	public void testPurgeAll() throws Exception {

		// Move the parent to the trash can
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setMethod("PUT");
		request.addHeader("Accept", "application/json");
		request.setRequestURI(UrlHelpers.TRASHCAN + "/trash/" + parent.getId());
		request.setParameter(AuthorizationConstants.USER_ID_PARAM, testId.toString());
		MockHttpServletResponse response = new MockHttpServletResponse();
		HttpServlet servlet = DispatchServletSingleton.getInstance();
		servlet.service(request, response);
		Assert.assertEquals(200, response.getStatus());
		
		// Purge the trash can
		request = new MockHttpServletRequest();
		request.setMethod("PUT");
		request.addHeader("Accept", "application/json");
		request.setRequestURI(UrlHelpers.TRASHCAN_PURGE);
		request.setParameter(AuthorizationConstants.USER_ID_PARAM, testId.toString());
		response = new MockHttpServletResponse();
		servlet = DispatchServletSingleton.getInstance();
		servlet.service(request, response);
		Assert.assertEquals(200, response.getStatus());

		// Both the parent and the child should be gone
		request = new MockHttpServletRequest();
		request.setMethod("GET");
		request.addHeader("Accept", "application/json");
		request.setRequestURI(UrlHelpers.ENTITY + "/" + parent.getId());
		request.setParameter(AuthorizationConstants.USER_ID_PARAM, testId.toString());
		response = new MockHttpServletResponse();
		servlet = DispatchServletSingleton.getInstance();
		servlet.service(request, response);
		Assert.assertEquals(404, response.getStatus());

		request = new MockHttpServletRequest();
		request.setMethod("GET");
		request.addHeader("Accept", "application/json");
		request.setRequestURI(UrlHelpers.ENTITY + "/" + child.getId());
		request.setParameter(AuthorizationConstants.USER_ID_PARAM, testId.toString());
		response = new MockHttpServletResponse();
		servlet = DispatchServletSingleton.getInstance();
		servlet.service(request, response);
		Assert.assertEquals(404, response.getStatus());

		// The trash can should be empty
		request = new MockHttpServletRequest();
		request.setMethod("GET");
		request.addHeader("Accept", "application/json");
		request.setRequestURI(UrlHelpers.TRASHCAN_VIEW);
		request.setParameter(AuthorizationConstants.USER_ID_PARAM, testId.toString());
		response = new MockHttpServletResponse();
		servlet = DispatchServletSingleton.getInstance();
		servlet.service(request, response);
		Assert.assertEquals(200, response.getStatus());
		String jsonStr = response.getContentAsString();
		JSONObjectAdapter adapter = new JSONObjectAdapterImpl(jsonStr);
		PaginatedResults<TrashedEntity> results = new PaginatedResults<TrashedEntity>(TrashedEntity.class);
		results.initializeFromJSONObject(adapter);
		Assert.assertEquals(0, results.getTotalNumberOfResults());
		Assert.assertEquals(0, results.getResults().size());
		for (TrashedEntity trash : results.getResults()) {
			if (parent.getId().equals(trash.getEntityId())
					|| child.getId().equals(trash.getEntityId())) {
				Assert.fail();
			}
		}

		// Already purged, no need to clean
		child = null;
		parent = null;
	}

	@Test
	public void testRoundTrip() throws Exception {

		// The trash can may not be empty before we put anything there
		// So we get base numbers first
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setMethod("GET");
		request.addHeader("Accept", "application/json");
		request.setRequestURI(UrlHelpers.TRASHCAN_VIEW);
		request.setParameter(AuthorizationConstants.USER_ID_PARAM, testId.toString());
		MockHttpServletResponse response = new MockHttpServletResponse();
		HttpServlet servlet = DispatchServletSingleton.getInstance();
		servlet.service(request, response);
		Assert.assertEquals(200, response.getStatus());
		String jsonStr = response.getContentAsString();
		JSONObjectAdapter adapter = new JSONObjectAdapterImpl(jsonStr);
		PaginatedResults<TrashedEntity> results = new PaginatedResults<TrashedEntity>(TrashedEntity.class);
		results.initializeFromJSONObject(adapter);
		long baseTotal = results.getTotalNumberOfResults();
		long baseCount = results.getResults().size();

		// Move the parent to the trash can
		request = new MockHttpServletRequest();
		request.setMethod("PUT");
		request.addHeader("Accept", "application/json");
		request.setRequestURI(UrlHelpers.TRASHCAN + "/trash/" + parent.getId());
		request.setParameter(AuthorizationConstants.USER_ID_PARAM, testId.toString());
		response = new MockHttpServletResponse();
		servlet = DispatchServletSingleton.getInstance();
		servlet.service(request, response);
		Assert.assertEquals(200, response.getStatus());

		// Now the parent and the child should not be visible
		request = new MockHttpServletRequest();
		request.setMethod("GET");
		request.addHeader("Accept", "application/json");
		request.setRequestURI(UrlHelpers.ENTITY + "/" + parent.getId());
		request.setParameter(AuthorizationConstants.USER_ID_PARAM, testId.toString());
		response = new MockHttpServletResponse();
		servlet = DispatchServletSingleton.getInstance();
		servlet.service(request, response);
		Assert.assertEquals(404, response.getStatus());

		// The parent and the child should be in the trash can
		request = new MockHttpServletRequest();
		request.setMethod("GET");
		request.addHeader("Accept", "application/json");
		request.setRequestURI(UrlHelpers.TRASHCAN_VIEW);
		request.setParameter(AuthorizationConstants.USER_ID_PARAM, testId.toString());
		response = new MockHttpServletResponse();
		servlet = DispatchServletSingleton.getInstance();
		servlet.service(request, response);

		Assert.assertEquals(200, response.getStatus());
		jsonStr = response.getContentAsString();
		adapter = new JSONObjectAdapterImpl(jsonStr);
		results = new PaginatedResults<TrashedEntity>(TrashedEntity.class);
		results.initializeFromJSONObject(adapter);
		Assert.assertEquals(baseTotal + 2L, results.getTotalNumberOfResults());
		Assert.assertEquals(baseCount + 2L, results.getResults().size());
		Set<String> idSet = new HashSet<String>();
		for (TrashedEntity trash : results.getResults()) {
			idSet.add(trash.getEntityId());
		}
		Assert.assertTrue(idSet.contains(parent.getId()));
		Assert.assertTrue(idSet.contains(child.getId()));

		// Restore the parent
		request = new MockHttpServletRequest();
		request.setMethod("PUT");
		request.addHeader("Accept", "application/json");
		request.setRequestURI(UrlHelpers.TRASHCAN + "/restore/" + parent.getId());
		request.setParameter(AuthorizationConstants.USER_ID_PARAM, testId.toString());
		response = new MockHttpServletResponse();
		servlet = DispatchServletSingleton.getInstance();
		servlet.service(request, response);
		Assert.assertEquals(200, response.getStatus());

		// Now the parent and the child should be visible again
		request = new MockHttpServletRequest();
		request.setMethod("GET");
		request.addHeader("Accept", "application/json");
		request.setRequestURI(UrlHelpers.ENTITY + "/" + parent.getId());
		request.setParameter(AuthorizationConstants.USER_ID_PARAM, testId.toString());
		response = new MockHttpServletResponse();
		servlet = DispatchServletSingleton.getInstance();
		servlet.service(request, response);
		Assert.assertEquals(200, response.getStatus());
		request = new MockHttpServletRequest();
		request.setMethod("GET");
		request.addHeader("Accept", "application/json");
		request.setRequestURI(UrlHelpers.ENTITY + "/" + child.getId());
		request.setParameter(AuthorizationConstants.USER_ID_PARAM, testId.toString());
		response = new MockHttpServletResponse();
		servlet = DispatchServletSingleton.getInstance();
		servlet.service(request, response);
		Assert.assertEquals(200, response.getStatus());

		// The parent and the child should not be in the trash can any more
		request = new MockHttpServletRequest();
		request.setMethod("GET");
		request.addHeader("Accept", "application/json");
		request.setRequestURI(UrlHelpers.TRASHCAN_VIEW);
		request.setParameter(AuthorizationConstants.USER_ID_PARAM, testId.toString());
		response = new MockHttpServletResponse();
		servlet = DispatchServletSingleton.getInstance();
		servlet.service(request, response);

		Assert.assertEquals(200, response.getStatus());
		jsonStr = response.getContentAsString();
		adapter = new JSONObjectAdapterImpl(jsonStr);
		results = new PaginatedResults<TrashedEntity>(TrashedEntity.class);
		results.initializeFromJSONObject(adapter);
		Assert.assertEquals(baseTotal, results.getTotalNumberOfResults());
		Assert.assertEquals(baseCount, results.getResults().size());
		idSet = new HashSet<String>();
		for (TrashedEntity trash : results.getResults()) {
			idSet.add(trash.getEntityId());
		}
		Assert.assertFalse(idSet.contains(parent.getId()));
		Assert.assertFalse(idSet.contains(child.getId()));
	}

	@Test
	public void testAdmin() throws Exception {

		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setMethod("PUT");
		request.addHeader("Accept", "application/json");
		request.setRequestURI(UrlHelpers.ADMIN_TRASHCAN_PURGE);
		request.setParameter(AuthorizationConstants.USER_ID_PARAM, adminId.toString());
		MockHttpServletResponse response = new MockHttpServletResponse();
		HttpServlet servlet = DispatchServletSingleton.getInstance();
		servlet.service(request, response);
		Assert.assertEquals(200, response.getStatus());

		request = new MockHttpServletRequest();
		request.setMethod("GET");
		request.addHeader("Accept", "application/json");
		request.setRequestURI(UrlHelpers.ADMIN_TRASHCAN_VIEW);
		request.setParameter(AuthorizationConstants.USER_ID_PARAM, adminId.toString());
		response = new MockHttpServletResponse();
		servlet = DispatchServletSingleton.getInstance();
		servlet.service(request, response);
		Assert.assertEquals(200, response.getStatus());
		String jsonStr = response.getContentAsString();
		JSONObjectAdapter adapter = new JSONObjectAdapterImpl(jsonStr);
		PaginatedResults<TrashedEntity> results = new PaginatedResults<TrashedEntity>(TrashedEntity.class);
		results.initializeFromJSONObject(adapter);
		Assert.assertEquals(0, results.getTotalNumberOfResults());
		Assert.assertEquals(0, results.getResults().size());

		// Move the parent to the trash can
		request = new MockHttpServletRequest();
		request.setMethod("PUT");
		request.addHeader("Accept", "application/json");
		request.setRequestURI(UrlHelpers.TRASHCAN + "/trash/" + parent.getId());
		request.setParameter(AuthorizationConstants.USER_ID_PARAM, adminId.toString());
		response = new MockHttpServletResponse();
		servlet = DispatchServletSingleton.getInstance();
		servlet.service(request, response);
		Assert.assertEquals(200, response.getStatus());

		request = new MockHttpServletRequest();
		request.setMethod("GET");
		request.addHeader("Accept", "application/json");
		request.setRequestURI(UrlHelpers.ADMIN_TRASHCAN_VIEW);
		request.setParameter(AuthorizationConstants.USER_ID_PARAM, adminId.toString());
		response = new MockHttpServletResponse();
		servlet = DispatchServletSingleton.getInstance();
		servlet.service(request, response);
		Assert.assertEquals(200, response.getStatus());
		jsonStr = response.getContentAsString();
		adapter = new JSONObjectAdapterImpl(jsonStr);
		results = new PaginatedResults<TrashedEntity>(TrashedEntity.class);
		results.initializeFromJSONObject(adapter);
		Assert.assertEquals(2, results.getTotalNumberOfResults());
		Assert.assertEquals(2, results.getResults().size());

		// Purge everything
		request = new MockHttpServletRequest();
		request.setMethod("PUT");
		request.addHeader("Accept", "application/json");
		request.setRequestURI(UrlHelpers.ADMIN_TRASHCAN_PURGE);
		request.setParameter(AuthorizationConstants.USER_ID_PARAM, adminId.toString());
		response = new MockHttpServletResponse();
		servlet = DispatchServletSingleton.getInstance();
		servlet.service(request, response);
		Assert.assertEquals(200, response.getStatus());

		// Both the parent and the child should be gone
		request = new MockHttpServletRequest();
		request.setMethod("GET");
		request.addHeader("Accept", "application/json");
		request.setRequestURI(UrlHelpers.ENTITY + "/" + parent.getId());
		request.setParameter(AuthorizationConstants.USER_ID_PARAM, adminId.toString());
		response = new MockHttpServletResponse();
		servlet = DispatchServletSingleton.getInstance();
		servlet.service(request, response);
		Assert.assertEquals(404, response.getStatus());

		request = new MockHttpServletRequest();
		request.setMethod("GET");
		request.addHeader("Accept", "application/json");
		request.setRequestURI(UrlHelpers.ENTITY + "/" + child.getId());
		request.setParameter(AuthorizationConstants.USER_ID_PARAM, adminId.toString());
		response = new MockHttpServletResponse();
		servlet = DispatchServletSingleton.getInstance();
		servlet.service(request, response);
		Assert.assertEquals(404, response.getStatus());

		// The trash can should be empty
		request = new MockHttpServletRequest();
		request.setMethod("GET");
		request.addHeader("Accept", "application/json");
		request.setRequestURI(UrlHelpers.ADMIN_TRASHCAN_VIEW);
		request.setParameter(AuthorizationConstants.USER_ID_PARAM, adminId.toString());
		response = new MockHttpServletResponse();
		servlet = DispatchServletSingleton.getInstance();
		servlet.service(request, response);
		Assert.assertEquals(200, response.getStatus());
		jsonStr = response.getContentAsString();
		adapter = new JSONObjectAdapterImpl(jsonStr);
		results = new PaginatedResults<TrashedEntity>(TrashedEntity.class);
		results.initializeFromJSONObject(adapter);
		Assert.assertEquals(0, results.getTotalNumberOfResults());
		Assert.assertEquals(0, results.getResults().size());

		// Already purged, no need to clean
		child = null;
		parent = null;
	}
}
