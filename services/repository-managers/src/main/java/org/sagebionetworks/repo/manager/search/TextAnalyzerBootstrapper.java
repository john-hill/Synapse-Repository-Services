package org.sagebionetworks.repo.manager.search;

import java.util.Optional;

import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.manager.schema.SynapseSchemaBootstrap;
import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dbo.search.TextAnalyzerDao;
import org.sagebionetworks.repo.model.schema.Organization;
import org.sagebionetworks.repo.model.search.table.TextAnalyzer;
import org.sagebionetworks.util.TemporaryCode;
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

	// Each analyzer's settings is a single OpenSearch settings.analysis JSON object
	// with one custom analyzer named "default" — the OpenSearch reserved name that lands
	// at analysis.analyzer.default for any index that picks this TextAnalyzer as its
	// SearchConfiguration.defaultAnalyzer. Bootstrapped analyzers contain no $ref entries;
	// users who want synonyms create their own TextAnalyzers that reference SynonymSet
	// qnames inside their filter map.

	private static final String SCIENTIFIC_SETTINGS = "{"
		+ "\"filter\":{"
			+ "\"sci_word_delimiter\":{"
				+ "\"type\":\"word_delimiter_graph\","
				+ "\"preserve_original\":true,"
				+ "\"split_on_case_change\":true,"
				+ "\"split_on_numerics\":true,"
				+ "\"catenate_words\":true,"
				+ "\"catenate_numbers\":false,"
				+ "\"stem_english_possessive\":true"
			+ "},"
			+ "\"english_stop\":{"
				+ "\"type\":\"stop\","
				+ "\"stopwords\":\"_english_\""
			+ "},"
			+ "\"english_stemmer\":{"
				+ "\"type\":\"stemmer\","
				+ "\"language\":\"english\""
			+ "}"
		+ "},"
		+ "\"analyzer\":{"
			+ "\"default\":{"
				+ "\"type\":\"custom\","
				+ "\"tokenizer\":\"standard\","
				+ "\"filter\":[\"sci_word_delimiter\",\"lowercase\",\"english_stop\",\"english_stemmer\"]"
			+ "}"
		+ "}"
	+ "}";

	private static final String STANDARD_SETTINGS = "{"
		+ "\"filter\":{"
			+ "\"std_word_delimiter\":{"
				+ "\"type\":\"word_delimiter_graph\","
				+ "\"preserve_original\":true,"
				+ "\"split_on_case_change\":true,"
				+ "\"split_on_numerics\":true,"
				+ "\"catenate_words\":true,"
				+ "\"catenate_numbers\":false,"
				+ "\"stem_english_possessive\":true"
			+ "}"
		+ "},"
		+ "\"analyzer\":{"
			+ "\"default\":{"
				+ "\"type\":\"custom\","
				+ "\"tokenizer\":\"standard\","
				+ "\"filter\":[\"std_word_delimiter\",\"lowercase\"]"
			+ "}"
		+ "}"
	+ "}";

	private static final String IDENTIFIER_SETTINGS = "{"
		+ "\"filter\":{"
			+ "\"id_word_delimiter\":{"
				+ "\"type\":\"word_delimiter_graph\","
				+ "\"preserve_original\":true,"
				+ "\"split_on_case_change\":true,"
				+ "\"split_on_numerics\":true,"
				+ "\"catenate_words\":true,"
				+ "\"catenate_numbers\":false,"
				+ "\"stem_english_possessive\":false"
			+ "}"
		+ "},"
		+ "\"analyzer\":{"
			+ "\"default\":{"
				+ "\"type\":\"custom\","
				+ "\"tokenizer\":\"whitespace\","
				+ "\"filter\":[\"id_word_delimiter\",\"lowercase\"]"
			+ "}"
		+ "}"
	+ "}";

	private static final String KEYWORD_SETTINGS = "{"
		+ "\"analyzer\":{"
			+ "\"default\":{"
				+ "\"type\":\"custom\","
				+ "\"tokenizer\":\"keyword\""
			+ "}"
		+ "}"
	+ "}";

	// AUTOCOMPLETE pairs an index-time edge_ngram chain with a non-ngram search-time chain in
	// one record. The index chain uses the legacy non-graph word_delimiter because
	// word_delimiter_graph emits multi-position graph tokens that edge_ngram cannot consume.
	private static final String AUTOCOMPLETE_SETTINGS = "{"
		+ "\"filter\":{"
			+ "\"ac_word_delimiter\":{"
				+ "\"type\":\"word_delimiter\","
				+ "\"preserve_original\":true,"
				+ "\"split_on_case_change\":true,"
				+ "\"split_on_numerics\":true,"
				+ "\"catenate_words\":true,"
				+ "\"catenate_numbers\":false,"
				+ "\"stem_english_possessive\":true"
			+ "},"
			+ "\"edge_ngram_filter\":{"
				+ "\"type\":\"edge_ngram\","
				+ "\"min_gram\":2,"
				+ "\"max_gram\":20"
			+ "},"
			+ "\"acs_word_delimiter\":{"
				+ "\"type\":\"word_delimiter_graph\","
				+ "\"preserve_original\":true,"
				+ "\"split_on_case_change\":true,"
				+ "\"split_on_numerics\":true,"
				+ "\"catenate_words\":true,"
				+ "\"catenate_numbers\":false,"
				+ "\"stem_english_possessive\":true"
			+ "}"
		+ "},"
		+ "\"analyzer\":{"
			+ "\"default\":{"
				+ "\"type\":\"custom\","
				+ "\"tokenizer\":\"standard\","
				+ "\"filter\":[\"ac_word_delimiter\",\"lowercase\",\"edge_ngram_filter\"]"
			+ "},"
			+ "\"default_search\":{"
				+ "\"type\":\"custom\","
				+ "\"tokenizer\":\"standard\","
				+ "\"filter\":[\"lowercase\",\"acs_word_delimiter\"]"
			+ "}"
		+ "}"
	+ "}";

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

		dropLegacyAutocompleteSearchAnalyzer();

		textAnalyzerDao.createOrUpdateSystemAnalyzerForBootstrapOnly(SCIENTIFIC_ID, buildAnalyzer(
				"SCIENTIFIC",
				"English stemming, stop words, lowercase. Best for scientific metadata.",
				SCIENTIFIC_SETTINGS
		), organizationName, adminUserId);

		textAnalyzerDao.createOrUpdateSystemAnalyzerForBootstrapOnly(STANDARD_ID, buildAnalyzer(
				"STANDARD",
				"OpenSearch standard analyzer. Unicode segmentation with lowercase. General-purpose.",
				STANDARD_SETTINGS
		), organizationName, adminUserId);

		textAnalyzerDao.createOrUpdateSystemAnalyzerForBootstrapOnly(IDENTIFIER_ID, buildAnalyzer(
				"IDENTIFIER",
				"Preserves punctuation. Whitespace tokenization plus lowercase. Suitable for DOIs, RRIDs, PMIDs.",
				IDENTIFIER_SETTINGS
		), organizationName, adminUserId);

		textAnalyzerDao.createOrUpdateSystemAnalyzerForBootstrapOnly(KEYWORD_ID, buildAnalyzer(
				"KEYWORD",
				"No tokenization. Entire value is a single token. Suitable for facet and filter fields.",
				KEYWORD_SETTINGS
		), organizationName, adminUserId);

		textAnalyzerDao.createOrUpdateSystemAnalyzerForBootstrapOnly(AUTOCOMPLETE_ID, buildAnalyzer(
				"AUTOCOMPLETE",
				"Edge n-gram (2-20 chars) for type-ahead, with a non-ngram analyzer.default_search for search time.",
				AUTOCOMPLETE_SETTINGS
		), organizationName, adminUserId);
	}

	private TextAnalyzer buildAnalyzer(String name, String description, String settings) {
		return new TextAnalyzer().setName(name).setDescription(description).setSettings(settings);
	}

	@TemporaryCode(author = "BryanFauble", comment = "Remove after every stack has been redeployed and the legacy AUTOCOMPLETE_SEARCH row from before AUTOCOMPLETE absorbed default_search no longer arrives. The name check guards the id from being clobbered if the slot is later reclaimed.")
	private void dropLegacyAutocompleteSearchAnalyzer() {
		Optional<TextAnalyzer> existing = textAnalyzerDao.get(LEGACY_AUTOCOMPLETE_SEARCH_ID);
		if (existing.isPresent() && LEGACY_AUTOCOMPLETE_SEARCH_NAME.equals(existing.get().getName())) {
			textAnalyzerDao.delete(LEGACY_AUTOCOMPLETE_SEARCH_ID);
		}
	}

	@TemporaryCode(author = "BryanFauble", comment = "Remove alongside dropLegacyAutocompleteSearchAnalyzer.")
	private static final long LEGACY_AUTOCOMPLETE_SEARCH_ID = 6L;
	@TemporaryCode(author = "BryanFauble", comment = "Remove alongside dropLegacyAutocompleteSearchAnalyzer.")
	private static final String LEGACY_AUTOCOMPLETE_SEARCH_NAME = "AUTOCOMPLETE_SEARCH";

}
