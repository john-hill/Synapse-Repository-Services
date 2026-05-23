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
 * resource-name regex and qualified-name regex.
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
}
