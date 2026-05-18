package org.sagebionetworks.repo.manager.search;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for the input-validation helpers in {@link SearchResourceConstants}:
 * resource-name regex, qualified-name regex, and the {@code *_path} JSON key rejection
 * gate that protects users from AOSS-incompatible custom analyzers.
 */
public class SearchResourceConstantsTest {

	// --- validateResourceName ---

	@ParameterizedTest
	@ValueSource(strings = {"a", "A", "my_analyzer", "Standard", "scientific_2", "X9_y_z"})
	public void testValidateResourceNameWithValidNames(String name) {
		// call under test
		assertDoesNotThrow(() -> SearchResourceConstants.validateResourceName(name));
	}

	@ParameterizedTest
	@ValueSource(strings = {"9starts_with_digit", "_starts_with_underscore", "has-dash", "has space", "has.dot", ""})
	public void testValidateResourceNameWithInvalidNamesThrows(String name) {
		// call under test
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> SearchResourceConstants.validateResourceName(name));
		assertEquals(SearchResourceConstants.RESOURCE_NAME_PATTERN_MSG, e.getMessage());
	}

	@Test
	public void testValidateResourceNameWithNullThrows() {
		// call under test
		assertThrows(IllegalArgumentException.class,
				() -> SearchResourceConstants.validateResourceName(null));
	}

	// --- validateQualifiedNameFormat ---

	@ParameterizedTest
	@ValueSource(strings = {"org.sagebionetworks-SCIENTIFIC", "biomed-medical_terms", "a-b", "x.y.z-myAnalyzer_2"})
	public void testValidateQualifiedNameFormatWithValidNames(String qname) {
		// call under test
		assertDoesNotThrow(() -> SearchResourceConstants.validateQualifiedNameFormat(qname, "field"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"no_hyphen", "-leading_hyphen", "trailing-", "org-9starts_with_digit", "org--double_hyphen"})
	public void testValidateQualifiedNameFormatWithInvalidNamesThrows(String qname) {
		// call under test
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> SearchResourceConstants.validateQualifiedNameFormat(qname, "myField"));
		assertTrue(e.getMessage().contains("Invalid qualified name format"));
		assertTrue(e.getMessage().contains("myField"),
				"Error must name the field for diagnostic clarity: " + e.getMessage());
	}

	// --- rejectFilePathParameters ---

	@Test
	public void testRejectFilePathParametersWithNullDoesNothing() {
		// call under test
		assertDoesNotThrow(() -> SearchResourceConstants.rejectFilePathParameters(null, "definition"));
	}

	@Test
	public void testRejectFilePathParametersWithEmptyDoesNothing() {
		// call under test
		assertDoesNotThrow(() -> SearchResourceConstants.rejectFilePathParameters("", "definition"));
	}

	@Test
	public void testRejectFilePathParametersWithInlineParamsPasses() {
		// All inline equivalents (synonyms, stopwords, mappings) are allowed.
		String def = "{\"type\":\"synonym_graph\",\"synonyms\":[\"a, b\"],\"expand\":true,\"lenient\":false}";
		// call under test
		assertDoesNotThrow(() -> SearchResourceConstants.rejectFilePathParameters(def, "definition"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"synonyms_path", "stopwords_path", "mappings_path",
			"protected_words_path", "hyphenation_patterns_path", "word_list_path"})
	public void testRejectFilePathParametersForeachKnownPathKeyThrows(String key) {
		// call under test
		String def = "{\"type\":\"x\",\"" + key + "\":\"foo.txt\"}";
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> SearchResourceConstants.rejectFilePathParameters(def, "definition"));
		assertTrue(e.getMessage().contains(key), "Error must name the offending key: " + e.getMessage());
		assertTrue(e.getMessage().contains("Amazon OpenSearch Serverless"));
	}

	@Test
	public void testRejectFilePathParametersWithFuturePathKeyAlsoThrows() {
		// Forward-compatibility: any `_path` key is rejected, not just a hard-coded list.
		String def = "{\"type\":\"future_filter\",\"some_future_path\":\"x\"}";
		// call under test
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> SearchResourceConstants.rejectFilePathParameters(def, "definition"));
		assertTrue(e.getMessage().contains("some_future_path"));
	}

	@Test
	public void testRejectFilePathParametersIncludesFieldNameInError() {
		String def = "{\"type\":\"stop\",\"stopwords_path\":\"analysis/stop.txt\"}";
		// call under test — the schema field name appears in the error so users find the source.
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> SearchResourceConstants.rejectFilePathParameters(def, "tokenFilters[my_stop].definition"));
		assertTrue(e.getMessage().contains("tokenFilters[my_stop].definition"),
				"Field name must be included for diagnostic clarity: " + e.getMessage());
	}

	@Test
	public void testRejectFilePathParametersDoesNotConfuseEmbeddedPath() {
		// A `synonyms` value that happens to contain `_path` in its STRING content is fine —
		// the regex matches the JSON KEY only, not the value.
		String def = "{\"type\":\"synonym_graph\",\"synonyms\":[\"path_a, path_b\"]}";
		// call under test
		assertDoesNotThrow(() -> SearchResourceConstants.rejectFilePathParameters(def, "definition"));
	}
}
