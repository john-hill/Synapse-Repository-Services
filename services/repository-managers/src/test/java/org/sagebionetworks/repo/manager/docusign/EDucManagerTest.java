package org.sagebionetworks.repo.manager.docusign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.docusign.DocuSignClient;
import org.sagebionetworks.repo.model.TeamConstants;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.educ.EDucTemplate;
import org.sagebionetworks.repo.model.educ.EDucTemplateListRequest;
import org.sagebionetworks.repo.model.educ.EDucTemplatePage;
import static org.sagebionetworks.repo.model.AuthorizationConstants.DEFAULT_REALM_ID;

@ExtendWith(MockitoExtension.class)
public class EDucManagerTest {

	@Mock
	private DocuSignClient mockDocuSignClient;

	@InjectMocks
	private EDucManager eDucManager;

	private UserInfo adminUser;
	private UserInfo actUser;
	private UserInfo regularUser;

	@BeforeEach
	public void before() {
		adminUser = new UserInfo(true, 1L, DEFAULT_REALM_ID);

		actUser = new UserInfo(false, 2L, DEFAULT_REALM_ID);
		actUser.setGroups(new HashSet<>(Collections.singleton(TeamConstants.ACT_TEAM_ID)));

		regularUser = new UserInfo(false, 3L, DEFAULT_REALM_ID);
		regularUser.setGroups(new HashSet<>());
	}

	private EDucTemplate template(String id) {
		EDucTemplate t = new EDucTemplate();
		t.setTemplateId(id);
		t.setName("template-" + id);
		return t;
	}

	@Test
	public void testListTemplatesWithNullUserInfo() {
		// call under test
		assertThrows(IllegalArgumentException.class,
				() -> eDucManager.listTemplates(null, new EDucTemplateListRequest()));
		verifyZeroInteractions(mockDocuSignClient);
	}

	@Test
	public void testListTemplatesWithNullRequest() {
		// call under test
		assertThrows(IllegalArgumentException.class,
				() -> eDucManager.listTemplates(adminUser, null));
		verifyZeroInteractions(mockDocuSignClient);
	}

	@Test
	public void testListTemplatesWithUnauthorizedUser() {
		// call under test
		assertThrows(UnauthorizedException.class,
				() -> eDucManager.listTemplates(regularUser, new EDucTemplateListRequest()));
		verifyZeroInteractions(mockDocuSignClient);
	}

	@Test
	public void testListTemplatesAsAdminFirstPageWithNoNextPage() throws Exception {
		// First page, default limit 50 → query with limit+1 = 51
		EDucTemplatePage clientPage = new EDucTemplatePage();
		clientPage.setResults(new ArrayList<>(Arrays.asList(template("a"), template("b"))));
		when(mockDocuSignClient.listTemplates(0, 51)).thenReturn(clientPage);

		// call under test
		EDucTemplatePage page = eDucManager.listTemplates(adminUser, new EDucTemplateListRequest());

		assertNotNull(page);
		assertEquals(2, page.getResults().size());
		assertNull(page.getNextPageToken());
		verify(mockDocuSignClient).listTemplates(0, 51);
	}

	@Test
	public void testListTemplatesAsACTFirstPageWithNextPage() throws Exception {
		// Full page returned (limit+1 results) → a next-page token is produced
		List<EDucTemplate> results = new ArrayList<>();
		for (int i = 0; i < 51; i++) {
			results.add(template(String.valueOf(i)));
		}
		EDucTemplatePage clientPage = new EDucTemplatePage();
		clientPage.setResults(results);
		when(mockDocuSignClient.listTemplates(0, 51)).thenReturn(clientPage);

		// call under test
		EDucTemplatePage page = eDucManager.listTemplates(actUser, new EDucTemplateListRequest());

		assertNotNull(page);
		assertEquals(50, page.getResults().size());
		assertNotNull(page.getNextPageToken());
	}

	@Test
	public void testListTemplatesHonorsIncomingNextPageToken() throws Exception {
		// Token "50a50" → limit=50, offset=50, getLimitForQuery=51
		EDucTemplatePage clientPage = new EDucTemplatePage();
		clientPage.setResults(new ArrayList<>(Arrays.asList(template("x"))));
		when(mockDocuSignClient.listTemplates(50, 51)).thenReturn(clientPage);

		EDucTemplateListRequest request = new EDucTemplateListRequest();
		request.setNextPageToken("50a50");

		// call under test
		EDucTemplatePage page = eDucManager.listTemplates(adminUser, request);

		assertEquals(1, page.getResults().size());
		assertNull(page.getNextPageToken());
		verify(mockDocuSignClient).listTemplates(50, 51);
	}

	@Test
	public void testListTemplatesWithEmptyClientResponse() throws Exception {
		EDucTemplatePage clientPage = new EDucTemplatePage();
		clientPage.setResults(new ArrayList<>());
		when(mockDocuSignClient.listTemplates(anyInt(), anyInt())).thenReturn(clientPage);

		// call under test
		EDucTemplatePage page = eDucManager.listTemplates(adminUser, new EDucTemplateListRequest());

		assertEquals(0, page.getResults().size());
		assertNull(page.getNextPageToken());
	}
}
