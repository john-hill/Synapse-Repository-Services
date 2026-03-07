package org.sagebionetworks.repo.model.dbo.search;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sagebionetworks.repo.model.table.search.TextAnalyzerSettings;

public class TextAnalyzerDaoJsonTest {

	@Test
	void testSettingsToJsonAndBack() {
		TextAnalyzerSettings settings = new TextAnalyzerSettings();
		settings.setTokenizer("standard");
		settings.setFilterOrder(Arrays.asList("lowercase", "english_stop"));
		settings.setSynonymAware(true);
		Map<String, String> tokenFilters = new HashMap<>();
		tokenFilters.put("english_stop", "{\"type\":\"stop\",\"stopwords\":\"_english_\"}");
		settings.setTokenFilters(tokenFilters);

		String json = TextAnalyzerDaoImpl.settingsToJson(settings);
		assertNotNull(json);
		TextAnalyzerSettings deserialized = TextAnalyzerDaoImpl.settingsFromJson(json);
		assertEquals("standard", deserialized.getTokenizer());
		assertEquals(Arrays.asList("lowercase", "english_stop"), deserialized.getFilterOrder());
		assertTrue(deserialized.getSynonymAware());
	}

	@Test
	void testNullSettingsReturnsEmptyJson() {
		assertEquals("{}", TextAnalyzerDaoImpl.settingsToJson(null));
	}

	@Test
	void testNullJsonReturnsEmptySettings() {
		assertNotNull(TextAnalyzerDaoImpl.settingsFromJson(null));
	}

	@Test
	void testEmptyJsonReturnsEmptySettings() {
		assertNotNull(TextAnalyzerDaoImpl.settingsFromJson(""));
	}

	@Test
	void testEdgeNgramFilterRoundTrip() {
		TextAnalyzerSettings settings = new TextAnalyzerSettings();
		settings.setTokenizer("standard");
		Map<String, String> tokenFilters = new HashMap<>();
		tokenFilters.put("edge_ngram_filter", "{\"type\":\"edge_ngram\",\"min_gram\":2,\"max_gram\":20}");
		settings.setTokenFilters(tokenFilters);
		settings.setFilterOrder(Arrays.asList("lowercase", "edge_ngram_filter"));

		String json = TextAnalyzerDaoImpl.settingsToJson(settings);
		TextAnalyzerSettings deserialized = TextAnalyzerDaoImpl.settingsFromJson(json);
		assertTrue(deserialized.getTokenFilters().containsKey("edge_ngram_filter"));
	}
}
