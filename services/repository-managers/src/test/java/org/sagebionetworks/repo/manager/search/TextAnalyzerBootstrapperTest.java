package org.sagebionetworks.repo.manager.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.manager.schema.SynapseSchemaBootstrap;
import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dbo.search.TextAnalyzerDao;
import org.sagebionetworks.repo.model.schema.Organization;
import org.sagebionetworks.repo.model.search.table.TextAnalyzer;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Verifies that {@link TextAnalyzerBootstrapper} idempotently upserts the five system
 * analyzers at the expected reserved IDs. Bootstrap runs in the constructor so loading
 * the bean triggers the upsert.
 */
@ExtendWith(MockitoExtension.class)
public class TextAnalyzerBootstrapperTest {

	private static final String ORG_NAME = "org.sagebionetworks";
	private static final Long ADMIN_USER_ID =
			AuthorizationConstants.BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId();

	@Mock
	private TextAnalyzerDao textAnalyzerDao;
	@Mock
	private SynapseSchemaBootstrap synapseSchemaBootstrap;
	@Mock
	private UserManager userManager;

	private final UserInfo adminUser = new UserInfo(true, ADMIN_USER_ID, "default-realm");

	@BeforeEach
	public void before() {
		when(userManager.getUserInfo(ADMIN_USER_ID)).thenReturn(adminUser);
		when(synapseSchemaBootstrap.createOrganizationIfDoesNotExist(adminUser))
				.thenReturn(new Organization().setName(ORG_NAME));
	}

	@Test
	public void testConstructorBootstrapsAllFiveSystemAnalyzersWithStableNamesAndIds() {
		// call under test
		Map<Long, TextAnalyzer> upserts = captureAllUpserts();

		assertEquals(5, upserts.size());
		assertEquals("SCIENTIFIC", upserts.get(TextAnalyzerBootstrapper.SCIENTIFIC_ID).getName());
		assertEquals("STANDARD", upserts.get(TextAnalyzerBootstrapper.STANDARD_ID).getName());
		assertEquals("IDENTIFIER", upserts.get(TextAnalyzerBootstrapper.IDENTIFIER_ID).getName());
		assertEquals("KEYWORD", upserts.get(TextAnalyzerBootstrapper.KEYWORD_ID).getName());
		assertEquals("AUTOCOMPLETE", upserts.get(TextAnalyzerBootstrapper.AUTOCOMPLETE_ID).getName());
	}

	@Test
	public void testEverySettingsBlobParsesAndDeclaresMainAnalyzer() {
		// Each bootstrapped analyzer's settings must (a) be valid JSON and (b) declare an
		// analyzer named "default" — the canonical entry the field-mapping side resolves to.
		for (TextAnalyzer a : captureAllUpserts().values()) {
			assertNotNull(a.getSettings(), "settings required for analyzer " + a.getName());
			JsonNode root = SearchOpaqueJsonUtil.parse(a.getSettings());
			JsonNode defaultEntry = root.at("/analyzer/default");
			assertTrue(defaultEntry.isObject(),
					"analyzer.default must exist and be an object: " + a.getName());
			assertEquals("custom", defaultEntry.get("type").asText(),
					"analyzer 'default' must be type=custom: " + a.getName());
		}
	}

	@Test
	public void testNoBootstrappedAnalyzerCarriesARefEntry() {
		// Bootstrapped analyzers don't reference user SynonymSets — users compose their own.
		for (TextAnalyzer a : captureAllUpserts().values()) {
			JsonNode root = SearchOpaqueJsonUtil.parse(a.getSettings());
			assertEquals(0, SearchOpaqueJsonUtil.collectRefs(root).size(),
					"bootstrapped analyzer '" + a.getName() + "' must not contain any $ref");
		}
	}

	// --- Stale-row migration for the dropped AUTOCOMPLETE_SEARCH_ID=6 ---

	@Test
	public void testBootstrapDeletesStaleAutocompleteSearchRowWhenNameMatches() {
		when(textAnalyzerDao.get(6L)).thenReturn(
				Optional.of(new TextAnalyzer().setName("AUTOCOMPLETE_SEARCH")));

		// call under test
		new TextAnalyzerBootstrapper(textAnalyzerDao, synapseSchemaBootstrap, userManager);

		verify(textAnalyzerDao).delete(6L);
	}

	@Test
	public void testBootstrapLeavesId6AloneWhenNameDoesNotMatch() {
		when(textAnalyzerDao.get(6L)).thenReturn(
				Optional.of(new TextAnalyzer().setName("RECLAIMED")));

		// call under test
		new TextAnalyzerBootstrapper(textAnalyzerDao, synapseSchemaBootstrap, userManager);

		verify(textAnalyzerDao, never()).delete(6L);
	}

	@Test
	public void testBootstrapLeavesId6AloneWhenAbsent() {
		when(textAnalyzerDao.get(6L)).thenReturn(Optional.empty());

		// call under test
		new TextAnalyzerBootstrapper(textAnalyzerDao, synapseSchemaBootstrap, userManager);

		verify(textAnalyzerDao, never()).delete(6L);
	}

	// --- helpers ---

	private Map<Long, TextAnalyzer> captureAllUpserts() {
		new TextAnalyzerBootstrapper(textAnalyzerDao, synapseSchemaBootstrap, userManager);
		ArgumentCaptor<Long> idCaptor = ArgumentCaptor.forClass(Long.class);
		ArgumentCaptor<TextAnalyzer> analyzerCaptor = ArgumentCaptor.forClass(TextAnalyzer.class);
		verify(textAnalyzerDao, times(5)).createOrUpdateSystemAnalyzerForBootstrapOnly(
				idCaptor.capture(), analyzerCaptor.capture(), eq(ORG_NAME), eq(ADMIN_USER_ID));

		Map<Long, TextAnalyzer> result = new HashMap<>();
		for (int i = 0; i < idCaptor.getAllValues().size(); i++) {
			result.put(idCaptor.getAllValues().get(i), analyzerCaptor.getAllValues().get(i));
		}
		return result;
	}
}
