package org.sagebionetworks.repo.manager.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
import org.sagebionetworks.repo.model.search.table.AnalyzerComponent;
import org.sagebionetworks.repo.model.search.table.TextAnalyzer;
import org.sagebionetworks.repo.model.search.table.TextAnalyzerSettings;

/**
 * Verifies that {@link TextAnalyzerBootstrapper} idempotently upserts the six system
 * analyzers at the expected reserved IDs, in the right order, with the right filter
 * chain and {@code synapse_synonyms} placeholder placement. The bootstrap method must
 * be invoked in the constructor (not via {@code afterPropertiesSet}) so loading the
 * bean triggers the upsert.
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
	public void testConstructorBootstrapsAllSixSystemAnalyzers() {
		// call under test — constructor runs bootstrap unconditionally.
		new TextAnalyzerBootstrapper(textAnalyzerDao, synapseSchemaBootstrap, userManager);

		// Each reserved ID 1..6 must be upserted exactly once.
		verify(textAnalyzerDao, times(6)).createOrUpdateSystemAnalyzerForBootstrapOnly(
				anyLong(), org.mockito.ArgumentMatchers.any(TextAnalyzer.class), eq(ORG_NAME), eq(ADMIN_USER_ID));
	}

	@Test
	public void testBootstrappedAnalyzersHaveStableNamesAndIds() {
		// Capture every upsert and assert the per-ID name mapping (analyzers are referenced
		// by qname elsewhere; renaming them would break every IT and downstream consumer).
		Map<Long, TextAnalyzer> upserts = captureAllUpserts();

		assertEquals("SCIENTIFIC", upserts.get(TextAnalyzerBootstrapper.SCIENTIFIC_ID).getName());
		assertEquals("STANDARD", upserts.get(TextAnalyzerBootstrapper.STANDARD_ID).getName());
		assertEquals("IDENTIFIER", upserts.get(TextAnalyzerBootstrapper.IDENTIFIER_ID).getName());
		assertEquals("KEYWORD", upserts.get(TextAnalyzerBootstrapper.KEYWORD_ID).getName());
		assertEquals("AUTOCOMPLETE", upserts.get(TextAnalyzerBootstrapper.AUTOCOMPLETE_ID).getName());
		assertEquals("AUTOCOMPLETE_SEARCH",
				upserts.get(TextAnalyzerBootstrapper.AUTOCOMPLETE_SEARCH_ID).getName());
	}

	@Test
	public void testScientificAnalyzerPlacesSynonymPlaceholderAfterLowercase() {
		// Synonym injection must happen after lowercase so synonym matching is case-insensitive,
		// and before stemming so 'cancer'→'cancer'→'cancer' (stem) survives.
		TextAnalyzer scientific = captureAllUpserts().get(TextAnalyzerBootstrapper.SCIENTIFIC_ID);
		List<String> order = scientific.getSettings().getIndexFilterOrder();

		int lowercaseIdx = order.indexOf("lowercase");
		int placeholderIdx = order.indexOf(OpenSearchManagerImpl.SYNONYM_PLACEHOLDER);
		int stopIdx = order.indexOf("english_stop");
		int stemmerIdx = order.indexOf("english_stemmer");

		assertTrue(lowercaseIdx >= 0, "SCIENTIFIC must contain lowercase: " + order);
		assertTrue(lowercaseIdx < placeholderIdx,
				"Placeholder must come AFTER lowercase (case-insensitive synonym match): " + order);
		assertTrue(placeholderIdx < stopIdx,
				"Placeholder must come BEFORE english_stop: " + order);
		assertTrue(stopIdx < stemmerIdx,
				"english_stop must come BEFORE stemmer: " + order);
	}

	@Test
	public void testStandardAndIdentifierAnalyzersExposePlaceholder() {
		Map<Long, TextAnalyzer> upserts = captureAllUpserts();

		List<String> standard = upserts.get(TextAnalyzerBootstrapper.STANDARD_ID)
				.getSettings().getIndexFilterOrder();
		List<String> identifier = upserts.get(TextAnalyzerBootstrapper.IDENTIFIER_ID)
				.getSettings().getIndexFilterOrder();

		assertTrue(standard.contains(OpenSearchManagerImpl.SYNONYM_PLACEHOLDER),
				"STANDARD must expose 'synapse_synonyms': " + standard);
		assertTrue(identifier.contains(OpenSearchManagerImpl.SYNONYM_PLACEHOLDER),
				"IDENTIFIER must expose 'synapse_synonyms': " + identifier);
	}

	@Test
	public void testKeywordAnalyzerHasNoTokenFilters() {
		// KEYWORD produces one token; synonym substitution against the whole field value is
		// almost never what users want, so the bootstrap intentionally omits the placeholder.
		TextAnalyzer keyword = captureAllUpserts().get(TextAnalyzerBootstrapper.KEYWORD_ID);

		assertEquals("keyword", keyword.getSettings().getTokenizer().getName());
		assertNotNull(keyword.getSettings(), "KEYWORD must have settings");
		// No indexFilterOrder set → no token filters in the chain.
		assertTrue(keyword.getSettings().getIndexFilterOrder() == null
				|| keyword.getSettings().getIndexFilterOrder().isEmpty(),
				"KEYWORD must have no filter chain: " + keyword.getSettings().getIndexFilterOrder());
	}

	@Test
	public void testAutocompleteAnalyzerOmitsPlaceholderBeforeEdgeNgram() {
		// edge_ngram is a non-graph filter that cannot consume the multi-position graph tokens
		// from synonym_graph; emitting synonyms before edge_ngram would explode the index.
		TextAnalyzer autocomplete = captureAllUpserts().get(TextAnalyzerBootstrapper.AUTOCOMPLETE_ID);
		List<String> order = autocomplete.getSettings().getIndexFilterOrder();

		assertTrue(order.contains("edge_ngram_filter"),
				"AUTOCOMPLETE must end with edge_ngram_filter: " + order);
		assertTrue(!order.contains(OpenSearchManagerImpl.SYNONYM_PLACEHOLDER),
				"AUTOCOMPLETE must NOT inject synonyms at index time: " + order);
	}

	@Test
	public void testAutocompleteSearchAnalyzerExposesPlaceholder() {
		// Search-time synonym expansion pairs with the index-time edge_ngram from AUTOCOMPLETE,
		// giving 'tumor'→'cancer' type-ahead without exploding the index.
		TextAnalyzer search = captureAllUpserts().get(TextAnalyzerBootstrapper.AUTOCOMPLETE_SEARCH_ID);
		List<String> order = search.getSettings().getIndexFilterOrder();

		assertTrue(order.contains(OpenSearchManagerImpl.SYNONYM_PLACEHOLDER),
				"AUTOCOMPLETE_SEARCH must expose 'synapse_synonyms' at search time: " + order);
	}

	@Test
	public void testBootstrappedComponentsAllCarryOpenSearchDefinitions() {
		// Every custom (non-built-in) token filter referenced in indexFilterOrder must be
		// declared in tokenFilters with a non-null definition; otherwise the translator can't
		// register it at index-build time and AOSS rejects the analyzer.
		for (Map.Entry<Long, TextAnalyzer> entry : captureAllUpserts().entrySet()) {
			TextAnalyzer a = entry.getValue();
			if (a.getSettings().getTokenFilters() == null) {
				continue;
			}
			for (AnalyzerComponent c : a.getSettings().getTokenFilters()) {
				assertNotNull(c.getDefinition(),
						"Owned token filter '" + c.getName() + "' in " + a.getName() + " must carry a definition");
			}
		}
	}

	// --- helpers ---

	private Map<Long, TextAnalyzer> captureAllUpserts() {
		new TextAnalyzerBootstrapper(textAnalyzerDao, synapseSchemaBootstrap, userManager);
		ArgumentCaptor<Long> idCaptor = ArgumentCaptor.forClass(Long.class);
		ArgumentCaptor<TextAnalyzer> analyzerCaptor = ArgumentCaptor.forClass(TextAnalyzer.class);
		verify(textAnalyzerDao, times(6)).createOrUpdateSystemAnalyzerForBootstrapOnly(
				idCaptor.capture(), analyzerCaptor.capture(), eq(ORG_NAME), eq(ADMIN_USER_ID));

		Map<Long, TextAnalyzer> result = new HashMap<>();
		for (int i = 0; i < idCaptor.getAllValues().size(); i++) {
			result.put(idCaptor.getAllValues().get(i), analyzerCaptor.getAllValues().get(i));
		}
		return result;
	}
}
