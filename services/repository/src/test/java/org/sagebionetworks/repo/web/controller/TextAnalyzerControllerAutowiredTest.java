package org.sagebionetworks.repo.web.controller;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.Test;
import org.sagebionetworks.repo.manager.search.TextAnalyzerBootstrap;
import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.table.search.ListTextAnalyzersRequest;
import org.sagebionetworks.repo.model.table.search.ListTextAnalyzersResponse;
import org.sagebionetworks.repo.model.table.search.TextAnalyzer;
import org.sagebionetworks.repo.web.NotFoundException;
import org.sagebionetworks.repo.web.UrlHelpers;
import org.sagebionetworks.repo.web.controller.ServletTestHelperUtils.HTTPMODE;
import org.sagebionetworks.schema.adapter.org.json.EntityFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Integration test for TextAnalyzerController endpoints.
 * The bootstrapper seeds 6 system analyzers at startup, so they are available for testing.
 */
public class TextAnalyzerControllerAutowiredTest extends AbstractAutowiredControllerTestBase {

	@Autowired
	private TextAnalyzerBootstrap textAnalyzerBootstrap;

	private Long adminUserId;

	@BeforeEach
	public void before() {
		adminUserId = AuthorizationConstants.BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId();
		textAnalyzerBootstrap.bootstrapSystemAnalyzers();
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
