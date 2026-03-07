package org.sagebionetworks.repo.manager.search;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.dbo.search.TextAnalyzerDao;
import org.sagebionetworks.repo.model.table.search.TextAnalyzer;
import org.sagebionetworks.repo.model.table.search.TextAnalyzerSettings;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Service;

@Service
public class TextAnalyzerBootstrapper implements InitializingBean {

	public static final long SCIENTIFIC_ID = 1L;
	public static final long STANDARD_ID = 2L;
	public static final long IDENTIFIER_ID = 3L;
	public static final long KEYWORD_ID = 4L;
	public static final long AUTOCOMPLETE_ID = 5L;
	public static final long AUTOCOMPLETE_SEARCH_ID = 6L;

	private final TextAnalyzerDao textAnalyzerDao;

	public TextAnalyzerBootstrapper(TextAnalyzerDao textAnalyzerDao) {
		this.textAnalyzerDao = textAnalyzerDao;
	}

	@Override
	public void afterPropertiesSet() {
		bootstrapSystemAnalyzers();
	}

	void bootstrapSystemAnalyzers() {
		Long adminUserId = AuthorizationConstants.BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId();

		// 1. SCIENTIFIC: English stemming, stop words, lowercase, synonym expansion
		textAnalyzerDao.createOrUpdateSystemAnalyzer(SCIENTIFIC_ID, buildAnalyzer(
				"SCIENTIFIC",
				"English stemming, stop words, lowercase, synonym expansion. Best for scientific metadata.",
				buildScientificSettings()
		), adminUserId);

		// 2. STANDARD: Standard tokenizer with lowercase
		textAnalyzerDao.createOrUpdateSystemAnalyzer(STANDARD_ID, buildAnalyzer(
				"STANDARD",
				"OpenSearch standard analyzer. Unicode segmentation with lowercase. General-purpose.",
				buildStandardSettings()
		), adminUserId);

		// 3. IDENTIFIER: Whitespace tokenizer with lowercase
		textAnalyzerDao.createOrUpdateSystemAnalyzer(IDENTIFIER_ID, buildAnalyzer(
				"IDENTIFIER",
				"Preserves punctuation. Whitespace tokenization plus lowercase. Suitable for DOIs, RRIDs, PMIDs.",
				buildIdentifierSettings()
		), adminUserId);

		// 4. KEYWORD: Built-in keyword analyzer
		textAnalyzerDao.createOrUpdateSystemAnalyzer(KEYWORD_ID, buildAnalyzer(
				"KEYWORD",
				"No tokenization. Entire value is a single token. Suitable for facet and filter fields.",
				buildKeywordSettings()
		), adminUserId);

		// 5. AUTOCOMPLETE: Edge n-gram for type-ahead
		textAnalyzerDao.createOrUpdateSystemAnalyzer(AUTOCOMPLETE_ID, buildAnalyzer(
				"AUTOCOMPLETE",
				"Edge n-gram (2-20 chars) for type-ahead. Paired with AUTOCOMPLETE_SEARCH at search time.",
				buildAutocompleteSettings()
		), adminUserId);

		// 6. AUTOCOMPLETE_SEARCH: Standard tokenizer with lowercase (search-time pair for AUTOCOMPLETE)
		textAnalyzerDao.createOrUpdateSystemAnalyzer(AUTOCOMPLETE_SEARCH_ID, buildAnalyzer(
				"AUTOCOMPLETE_SEARCH",
				"Search-time analyzer paired with AUTOCOMPLETE. Standard tokenizer with lowercase and synonyms.",
				buildAutocompleteSearchSettings()
		), adminUserId);
	}

	private TextAnalyzer buildAnalyzer(String name, String description, TextAnalyzerSettings settings) {
		TextAnalyzer analyzer = new TextAnalyzer();
		analyzer.setName(name);
		analyzer.setDescription(description);
		analyzer.setSettings(settings);
		return analyzer;
	}

	private TextAnalyzerSettings buildScientificSettings() {
		TextAnalyzerSettings settings = new TextAnalyzerSettings();
		settings.setTokenizer("standard");

		Map<String, String> tokenFilters = new HashMap<>();
		tokenFilters.put("sci_word_delimiter",
				"{\"type\":\"word_delimiter_graph\",\"preserve_original\":true,"
				+ "\"split_on_case_change\":true,\"split_on_numerics\":true,"
				+ "\"catenate_words\":true,\"catenate_numbers\":false,"
				+ "\"stem_english_possessive\":true}");
		tokenFilters.put("english_stop", "{\"type\":\"stop\",\"stopwords\":\"_english_\"}");
		tokenFilters.put("english_stemmer", "{\"type\":\"stemmer\",\"language\":\"english\"}");

		settings.setTokenFilters(tokenFilters);
		settings.setFilterOrder(Arrays.asList(
				"sci_word_delimiter", "lowercase", "english_stop", "english_stemmer"));
		settings.setSynonymAware(true);
		return settings;
	}

	private TextAnalyzerSettings buildStandardSettings() {
		TextAnalyzerSettings settings = new TextAnalyzerSettings();
		settings.setTokenizer("standard");

		Map<String, String> tokenFilters = new HashMap<>();
		tokenFilters.put("std_word_delimiter",
				"{\"type\":\"word_delimiter_graph\",\"preserve_original\":true,"
				+ "\"split_on_case_change\":true,\"split_on_numerics\":true,"
				+ "\"catenate_words\":true,\"catenate_numbers\":false,"
				+ "\"stem_english_possessive\":true}");

		settings.setTokenFilters(tokenFilters);
		settings.setFilterOrder(Arrays.asList("std_word_delimiter", "lowercase"));
		settings.setSynonymAware(true);
		return settings;
	}

	private TextAnalyzerSettings buildIdentifierSettings() {
		TextAnalyzerSettings settings = new TextAnalyzerSettings();
		settings.setTokenizer("whitespace");

		Map<String, String> tokenFilters = new HashMap<>();
		tokenFilters.put("id_word_delimiter",
				"{\"type\":\"word_delimiter_graph\",\"preserve_original\":true,"
				+ "\"split_on_case_change\":true,\"split_on_numerics\":true,"
				+ "\"catenate_words\":true,\"catenate_numbers\":false,"
				+ "\"stem_english_possessive\":false}");

		settings.setTokenFilters(tokenFilters);
		settings.setFilterOrder(Arrays.asList("id_word_delimiter", "lowercase"));
		settings.setSynonymAware(true);
		return settings;
	}

	private TextAnalyzerSettings buildKeywordSettings() {
		TextAnalyzerSettings settings = new TextAnalyzerSettings();
		settings.setTokenizer("keyword");
		return settings;
	}

	private TextAnalyzerSettings buildAutocompleteSettings() {
		TextAnalyzerSettings settings = new TextAnalyzerSettings();
		settings.setTokenizer("standard");

		Map<String, String> tokenFilters = new HashMap<>();
		tokenFilters.put("ac_word_delimiter",
				"{\"type\":\"word_delimiter_graph\",\"preserve_original\":true,"
				+ "\"split_on_case_change\":true,\"split_on_numerics\":true,"
				+ "\"catenate_words\":true,\"catenate_numbers\":false,"
				+ "\"stem_english_possessive\":true}");
		tokenFilters.put("edge_ngram_filter", "{\"type\":\"edge_ngram\",\"min_gram\":2,\"max_gram\":20}");

		settings.setTokenFilters(tokenFilters);
		settings.setFilterOrder(Arrays.asList("ac_word_delimiter", "lowercase", "edge_ngram_filter"));
		settings.setSynonymAware(false);
		return settings;
	}

	private TextAnalyzerSettings buildAutocompleteSearchSettings() {
		TextAnalyzerSettings settings = new TextAnalyzerSettings();
		settings.setTokenizer("standard");

		Map<String, String> tokenFilters = new HashMap<>();
		tokenFilters.put("acs_word_delimiter",
				"{\"type\":\"word_delimiter_graph\",\"preserve_original\":true,"
				+ "\"split_on_case_change\":true,\"split_on_numerics\":true,"
				+ "\"catenate_words\":true,\"catenate_numbers\":false,"
				+ "\"stem_english_possessive\":true}");

		settings.setTokenFilters(tokenFilters);
		settings.setFilterOrder(Arrays.asList("acs_word_delimiter", "lowercase"));
		settings.setSynonymAware(true);
		return settings;
	}
}
