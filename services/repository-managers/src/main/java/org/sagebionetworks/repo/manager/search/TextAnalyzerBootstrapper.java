package org.sagebionetworks.repo.manager.search;

import java.util.Arrays;
import java.util.Collections;

import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.manager.schema.SynapseSchemaBootstrap;
import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dbo.search.TextAnalyzerDao;
import org.sagebionetworks.repo.model.schema.Organization;
import org.sagebionetworks.repo.model.search.table.AnalyzerComponent;
import org.sagebionetworks.repo.model.search.table.TextAnalyzer;
import org.sagebionetworks.repo.model.search.table.TextAnalyzerSettings;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

@Service
@DependsOn({"updateDefaultRealm", "teamManager"})
public class TextAnalyzerBootstrapper implements TextAnalyzerBootstrap {

	public static final long SCIENTIFIC_ID = 1L;
	public static final long STANDARD_ID = 2L;
	public static final long IDENTIFIER_ID = 3L;
	public static final long KEYWORD_ID = 4L;
	public static final long AUTOCOMPLETE_ID = 5L;
	public static final long AUTOCOMPLETE_SEARCH_ID = 6L;

	// Reusable token-filter definitions. Bootstrapped analyzers are symmetric (same chain
	// at index and search time) and contain no synonym references — users who want synonym
	// expansion create their own TextAnalyzers that reference SynonymSet qnames directly
	// from indexFilterOrder / searchFilterOrder.
	private static final String WORD_DELIMITER_GRAPH_DEF =
			"{\"type\":\"word_delimiter_graph\",\"preserve_original\":true,"
			+ "\"split_on_case_change\":true,\"split_on_numerics\":true,"
			+ "\"catenate_words\":true,\"catenate_numbers\":false,"
			+ "\"stem_english_possessive\":true}";
	private static final String WORD_DELIMITER_NO_POSSESSIVE_DEF =
			"{\"type\":\"word_delimiter_graph\",\"preserve_original\":true,"
			+ "\"split_on_case_change\":true,\"split_on_numerics\":true,"
			+ "\"catenate_words\":true,\"catenate_numbers\":false,"
			+ "\"stem_english_possessive\":false}";
	private static final String WORD_DELIMITER_NON_GRAPH_DEF =
			"{\"type\":\"word_delimiter\",\"preserve_original\":true,"
			+ "\"split_on_case_change\":true,\"split_on_numerics\":true,"
			+ "\"catenate_words\":true,\"catenate_numbers\":false,"
			+ "\"stem_english_possessive\":true}";
	private static final String EDGE_NGRAM_FILTER_DEF =
			"{\"type\":\"edge_ngram\",\"min_gram\":2,\"max_gram\":20}";
	private static final String ENGLISH_STOP_DEF =
			"{\"type\":\"stop\",\"stopwords\":\"_english_\"}";
	private static final String ENGLISH_STEMMER_DEF =
			"{\"type\":\"stemmer\",\"language\":\"english\"}";

	private final TextAnalyzerDao textAnalyzerDao;
	private final SynapseSchemaBootstrap synapseSchemaBootstrap;
	private final UserManager userManager;

	public TextAnalyzerBootstrapper(TextAnalyzerDao textAnalyzerDao,
			SynapseSchemaBootstrap synapseSchemaBootstrap, UserManager userManager) {
		this.textAnalyzerDao = textAnalyzerDao;
		this.synapseSchemaBootstrap = synapseSchemaBootstrap;
		this.userManager = userManager;
		bootstrapSystemAnalyzers();
	}

	@Override
	public void bootstrapSystemAnalyzers() {
		UserInfo adminUser = userManager.getUserInfo(AuthorizationConstants.BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId());
		Organization organization = synapseSchemaBootstrap.createOrganizationIfDoesNotExist(adminUser);
		Long adminUserId = AuthorizationConstants.BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId();
		String organizationName = organization.getName();

		textAnalyzerDao.createOrUpdateSystemAnalyzerForBootstrapOnly(SCIENTIFIC_ID, buildAnalyzer(
				"SCIENTIFIC",
				"English stemming, stop words, lowercase. Best for scientific metadata.",
				buildScientificSettings()
		), organizationName, adminUserId);

		textAnalyzerDao.createOrUpdateSystemAnalyzerForBootstrapOnly(STANDARD_ID, buildAnalyzer(
				"STANDARD",
				"OpenSearch standard analyzer. Unicode segmentation with lowercase. General-purpose.",
				buildStandardSettings()
		), organizationName, adminUserId);

		textAnalyzerDao.createOrUpdateSystemAnalyzerForBootstrapOnly(IDENTIFIER_ID, buildAnalyzer(
				"IDENTIFIER",
				"Preserves punctuation. Whitespace tokenization plus lowercase. Suitable for DOIs, RRIDs, PMIDs.",
				buildIdentifierSettings()
		), organizationName, adminUserId);

		textAnalyzerDao.createOrUpdateSystemAnalyzerForBootstrapOnly(KEYWORD_ID, buildAnalyzer(
				"KEYWORD",
				"No tokenization. Entire value is a single token. Suitable for facet and filter fields.",
				buildKeywordSettings()
		), organizationName, adminUserId);

		textAnalyzerDao.createOrUpdateSystemAnalyzerForBootstrapOnly(AUTOCOMPLETE_ID, buildAnalyzer(
				"AUTOCOMPLETE",
				"Edge n-gram (2-20 chars) for type-ahead. Paired with AUTOCOMPLETE_SEARCH at search time.",
				buildAutocompleteSettings()
		), organizationName, adminUserId);

		textAnalyzerDao.createOrUpdateSystemAnalyzerForBootstrapOnly(AUTOCOMPLETE_SEARCH_ID, buildAnalyzer(
				"AUTOCOMPLETE_SEARCH",
				"Search-time analyzer paired with AUTOCOMPLETE. Standard tokenizer with lowercase.",
				buildAutocompleteSearchSettings()
		), organizationName, adminUserId);
	}

	private TextAnalyzer buildAnalyzer(String name, String description, TextAnalyzerSettings settings) {
		return new TextAnalyzer().setName(name).setDescription(description).setSettings(settings);
	}

	private static AnalyzerComponent builtIn(String name) {
		return new AnalyzerComponent().setName(name);
	}

	private static AnalyzerComponent custom(String name, String definition) {
		return new AnalyzerComponent().setName(name).setDefinition(definition);
	}

	// Reserved injection-point token expanded at index-build time into the SynonymSet
	// qnames listed on the SearchConfiguration. Lives in searchFilterOrder only —
	// synonym_graph is a search-time filter by design (index-time use forces re-index
	// on dictionary change, bloats storage, distorts TF scoring). OpenSearch compiles
	// the synonym dictionary at index-init by feeding raw synonyms through every filter
	// preceding the placeholder, so any graph-emitting filter (word_delimiter_graph,
	// edge_ngram, ...) must come AFTER 'synapse_synonyms' in the chain or OpenSearch
	// rejects the index with "cannot be used to parse synonyms".
	private static final String SYN = "synapse_synonyms";

	private TextAnalyzerSettings buildScientificSettings() {
		return new TextAnalyzerSettings()
				.setTokenizer(builtIn("standard"))
				.setTokenFilters(Arrays.asList(
						custom("sci_word_delimiter", WORD_DELIMITER_GRAPH_DEF),
						custom("english_stop", ENGLISH_STOP_DEF),
						custom("english_stemmer", ENGLISH_STEMMER_DEF)))
				.setIndexFilterOrder(Arrays.asList(
						"sci_word_delimiter", "lowercase", "english_stop", "english_stemmer"))
				.setSearchFilterOrder(Arrays.asList(
						"lowercase", SYN, "sci_word_delimiter", "english_stop", "english_stemmer"));
	}

	private TextAnalyzerSettings buildStandardSettings() {
		return new TextAnalyzerSettings()
				.setTokenizer(builtIn("standard"))
				.setTokenFilters(Collections.singletonList(
						custom("std_word_delimiter", WORD_DELIMITER_GRAPH_DEF)))
				.setIndexFilterOrder(Arrays.asList("std_word_delimiter", "lowercase"))
				.setSearchFilterOrder(Arrays.asList("lowercase", SYN, "std_word_delimiter"));
	}

	private TextAnalyzerSettings buildIdentifierSettings() {
		return new TextAnalyzerSettings()
				.setTokenizer(builtIn("whitespace"))
				.setTokenFilters(Collections.singletonList(
						custom("id_word_delimiter", WORD_DELIMITER_NO_POSSESSIVE_DEF)))
				.setIndexFilterOrder(Arrays.asList("id_word_delimiter", "lowercase"))
				.setSearchFilterOrder(Arrays.asList("lowercase", SYN, "id_word_delimiter"));
	}

	private TextAnalyzerSettings buildKeywordSettings() {
		// KEYWORD tokenizer produces one token (the whole field value). Whole-value synonym
		// matching is rarely what users want, so no placeholder is exposed.
		return new TextAnalyzerSettings().setTokenizer(builtIn("keyword"));
	}

	/**
	 * AUTOCOMPLETE is an index-time analyzer ending in edge_ngram. word_delimiter_graph emits
	 * multi-position graph tokens which edge_ngram (a non-graph filter) cannot consume, so the
	 * legacy non-graph word_delimiter is used here. No synonym placeholder at index time —
	 * synonyms before edge_ngram explode the index; users get synonym matching at search time
	 * via the paired AUTOCOMPLETE_SEARCH analyzer.
	 */
	private TextAnalyzerSettings buildAutocompleteSettings() {
		return new TextAnalyzerSettings()
				.setTokenizer(builtIn("standard"))
				.setTokenFilters(Arrays.asList(
						custom("ac_word_delimiter", WORD_DELIMITER_NON_GRAPH_DEF),
						custom("edge_ngram_filter", EDGE_NGRAM_FILTER_DEF)))
				.setIndexFilterOrder(Arrays.asList("ac_word_delimiter", "lowercase", "edge_ngram_filter"));
	}

	private TextAnalyzerSettings buildAutocompleteSearchSettings() {
		return new TextAnalyzerSettings()
				.setTokenizer(builtIn("standard"))
				.setTokenFilters(Collections.singletonList(
						custom("acs_word_delimiter", WORD_DELIMITER_GRAPH_DEF)))
				.setIndexFilterOrder(Arrays.asList("lowercase", SYN, "acs_word_delimiter"));
	}

}
