package org.sagebionetworks.repo.web.controller;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.Test;
import org.sagebionetworks.repo.manager.search.TextAnalyzerBootstrap;
import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.dbo.search.TextAnalyzerDao;
import org.sagebionetworks.repo.model.table.search.ListTextAnalyzersRequest;
import org.sagebionetworks.repo.model.table.search.ListTextAnalyzersResponse;
import org.sagebionetworks.repo.model.table.search.TextAnalyzer;
import org.sagebionetworks.repo.model.table.search.TextAnalyzerSettings;
import org.sagebionetworks.repo.web.NotFoundException;
import org.sagebionetworks.repo.web.UrlHelpers;
import org.sagebionetworks.repo.web.controller.ServletTestHelperUtils.HTTPMODE;
import org.sagebionetworks.schema.adapter.org.json.EntityFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Integration test for TextAnalyzerController endpoints.
 * The bootstrapper seeds 6 system analyzers at startup, so they are available for testing.
 */
public class TextAnalyzerControllerAutowiredTest extends AbstractAutowiredControllerTestBase {

	@Autowired
	private TextAnalyzerDao textAnalyzerDao;

	@Autowired
	private TextAnalyzerBootstrap textAnalyzerBootstrap;

	private Long adminUserId;
	private List<Long> createdIds;

	@BeforeEach
	public void before() {
		adminUserId = AuthorizationConstants.BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId();
		createdIds = new ArrayList<>();
        textAnalyzerBootstrap.bootstrapSystemAnalyzers();
	}

	@AfterEach
	public void after() throws Exception {
		for (Long id : createdIds) {
			try {
				deleteTextAnalyzer(id);
			} catch (Exception e) {
				// ignore — may have already been deleted in the test
			}
		}
	}

	@Test
	public void testGetAllBootstrappedAnalyzers() throws Exception {
		// Verify all 6 system analyzers are accessible
		String[] expectedNames = { "SCIENTIFIC", "STANDARD", "IDENTIFIER", "KEYWORD", "AUTOCOMPLETE", "AUTOCOMPLETE_SEARCH" };
		for (long id = 1; id <= 6; id++) {
			TextAnalyzer result = getTextAnalyzer(id);
			assertEquals(String.valueOf(id), result.getId());
			assertEquals(expectedNames[(int) id - 1], result.getName());
		}
	}

	@Test
	public void testGetNotFound() throws Exception {
		assertThrows(NotFoundException.class, () -> getTextAnalyzer(999999L));
	}

	@Test
	public void testListSystemAnalyzers() throws Exception {
		ListTextAnalyzersRequest request = new ListTextAnalyzersRequest();

		ListTextAnalyzersResponse response = listTextAnalyzers(request);

		assertNotNull(response);
		assertNotNull(response.getResults());
		// At minimum, the 6 bootstrapped system analyzers
		assertTrue(response.getResults().size() >= 6);
		for (TextAnalyzer analyzer : response.getResults()) {
			assertNotNull(analyzer.getOrganizationId());
		}
	}

	@Test
	public void testCreateAndGet() throws Exception {
		TextAnalyzer analyzer = new TextAnalyzer();
		analyzer.setOrganizationId("7");
		analyzer.setName("test-analyzer");
		TextAnalyzerSettings settings = new TextAnalyzerSettings();
		settings.setTokenizer("standard");
		settings.setFilterOrder(Arrays.asList("lowercase"));
		analyzer.setSettings(settings);

		TextAnalyzer created = createTextAnalyzer(analyzer);

		assertNotNull(created.getId());
		assertNotNull(created.getEtag());
		assertNotNull(created.getCreatedOn());
		assertNotNull(created.getCreatedBy());

		TextAnalyzer fetched = getTextAnalyzer(Long.parseLong(created.getId()));

		assertEquals(created.getId(), fetched.getId());
		assertEquals(created.getName(), fetched.getName());
		assertEquals(created.getOrganizationId(), fetched.getOrganizationId());
	}

	@Test
	public void testUpdateRoundTrip() throws Exception {
		TextAnalyzer analyzer = new TextAnalyzer();
		analyzer.setOrganizationId("7");
		analyzer.setName("original-name");
		TextAnalyzerSettings settings = new TextAnalyzerSettings();
		settings.setTokenizer("standard");
		analyzer.setSettings(settings);

		TextAnalyzer created = createTextAnalyzer(analyzer);

		created.setName("updated-name");
		created.setDescription("updated-desc");

		TextAnalyzer updated = updateTextAnalyzer(Long.parseLong(created.getId()), created);

		assertEquals("updated-name", updated.getName());
		assertEquals("updated-desc", updated.getDescription());
		assertNotEquals(created.getEtag(), updated.getEtag());
	}

	@Test
	public void testDelete() throws Exception {
		TextAnalyzer analyzer = new TextAnalyzer();
		analyzer.setOrganizationId("7");
		analyzer.setName("to-be-deleted");
		TextAnalyzerSettings settings = new TextAnalyzerSettings();
		settings.setTokenizer("standard");
		analyzer.setSettings(settings);

		TextAnalyzer created = createTextAnalyzer(analyzer);
		Long id = Long.parseLong(created.getId());

		// Remove from cleanup list since we delete it here
		createdIds.remove(id);

		deleteTextAnalyzer(id);

		assertThrows(NotFoundException.class, () -> getTextAnalyzer(id));
	}

	@Test
	public void testUpdateWithPathBodyIdMismatch() throws Exception {
		TextAnalyzer analyzer = new TextAnalyzer();
		analyzer.setOrganizationId("7");
		analyzer.setName("mismatch-test");
		TextAnalyzerSettings settings = new TextAnalyzerSettings();
		settings.setTokenizer("standard");
		analyzer.setSettings(settings);

		TextAnalyzer created = createTextAnalyzer(analyzer);

		// Use a path ID that differs from the body's ID
		long pathId = 999999L;
		// The body retains the real created ID
		assertThrows(IllegalArgumentException.class, () -> updateTextAnalyzer(pathId, created));
	}

	private TextAnalyzer createTextAnalyzer(TextAnalyzer body) throws Exception {
		MockHttpServletRequest request = ServletTestHelperUtils.initRequest(
				HTTPMODE.POST, UrlHelpers.SEARCH_TEXT_ANALYZER,
				adminUserId, null, body);
		MockHttpServletResponse response = ServletTestHelperUtils.dispatchRequest(
				dispatchServlet, request, HttpStatus.CREATED);
		TextAnalyzer result = EntityFactory.createEntityFromJSONString(response.getContentAsString(), TextAnalyzer.class);
		createdIds.add(Long.parseLong(result.getId()));
		return result;
	}

	private TextAnalyzer updateTextAnalyzer(Long id, TextAnalyzer body) throws Exception {
		MockHttpServletRequest request = ServletTestHelperUtils.initRequest(
				HTTPMODE.PUT, UrlHelpers.SEARCH_TEXT_ANALYZER + "/" + id,
				adminUserId, null, body);
		MockHttpServletResponse response = ServletTestHelperUtils.dispatchRequest(
				dispatchServlet, request, HttpStatus.OK);
		return EntityFactory.createEntityFromJSONString(response.getContentAsString(), TextAnalyzer.class);
	}

	private void deleteTextAnalyzer(Long id) throws Exception {
		MockHttpServletRequest request = ServletTestHelperUtils.initRequest(
				HTTPMODE.DELETE, UrlHelpers.SEARCH_TEXT_ANALYZER + "/" + id,
				adminUserId, null, null);
		ServletTestHelperUtils.dispatchRequest(dispatchServlet, request, HttpStatus.NO_CONTENT);
	}

	private TextAnalyzer getTextAnalyzer(Long id) throws Exception {
		MockHttpServletRequest request = ServletTestHelperUtils.initRequest(
				HTTPMODE.GET, UrlHelpers.SEARCH_TEXT_ANALYZER + "/" + id,
				adminUserId, null, null);
		MockHttpServletResponse response = ServletTestHelperUtils.dispatchRequest(
				dispatchServlet, request, HttpStatus.OK);
		return EntityFactory.createEntityFromJSONString(response.getContentAsString(), TextAnalyzer.class);
	}

	private ListTextAnalyzersResponse listTextAnalyzers(ListTextAnalyzersRequest listRequest) throws Exception {
		MockHttpServletRequest request = ServletTestHelperUtils.initRequest(
				HTTPMODE.POST, UrlHelpers.SEARCH_TEXT_ANALYZER_LIST,
				adminUserId, null, listRequest);
		MockHttpServletResponse response = ServletTestHelperUtils.dispatchRequest(
				dispatchServlet, request, HttpStatus.OK);
		return EntityFactory.createEntityFromJSONString(response.getContentAsString(), ListTextAnalyzersResponse.class);
	}
}
