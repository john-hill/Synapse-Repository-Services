package org.sagebionetworks.repo.manager.search;

import java.util.regex.Pattern;

import org.sagebionetworks.util.ValidateArgument;

/**
 * Shared constants and validation utilities for search management resources
 * (TextAnalyzer, SynonymSet, ColumnAnalyzerOverride, SearchConfiguration).
 */
public final class SearchResourceConstants {

	/**
	 * Pattern for resource names. Must start with a letter, followed by letters, digits, or underscores.
	 */
	public static final String RESOURCE_NAME_PATTERN = "^[a-zA-Z][a-zA-Z0-9_]*$";

	/**
	 * Regex for a qualified name: {organizationName}-{resourceName}.
	 * Organization names are dot-separated alphanumeric segments (e.g., "org.sagebionetworks").
	 * Resource names start with a letter and contain letters, digits, and underscores.
	 * The separator is a hyphen, which never appears in organization names.
	 */
	static final Pattern QUALIFIED_NAME_PATTERN = Pattern.compile(
			"^[a-zA-Z0-9]+(\\.[a-zA-Z0-9]+)*-[a-zA-Z][a-zA-Z0-9_]*$");

	/**
	 * Error message when a resource name does not match {@link #RESOURCE_NAME_PATTERN}.
	 */
	public static final String RESOURCE_NAME_PATTERN_MSG = "Resource name must start with a letter and contain only letters, digits, and underscores.";

	/**
	 * Error message when a name change is attempted on update.
	 */
	public static final String NAME_IMMUTABLE_MSG = "The name cannot be changed. Create a new resource instead.";

	/**
	 * Error message when an organizationName change is attempted on update.
	 */
	public static final String ORG_NAME_IMMUTABLE_MSG = "The organizationName cannot be changed.";

	/**
	 * Validates that a resource name matches the required pattern.
	 *
	 * @param name The resource name to validate
	 * @throws IllegalArgumentException if the name does not match the pattern
	 */
	public static void validateResourceName(String name) {
		ValidateArgument.required(name, "name");
		if (!name.matches(RESOURCE_NAME_PATTERN)) {
			throw new IllegalArgumentException(RESOURCE_NAME_PATTERN_MSG);
		}
	}

	/**
	 * Validates that a qualified name has the correct format: {organizationName}-{resourceName}.
	 * Organization names are dot-separated alphanumeric segments (e.g., "org.sagebionetworks").
	 * Resource names start with a letter and contain letters, digits, and underscores.
	 * The separator is a hyphen, which never appears in organization names.
	 *
	 * @param qualifiedName The qualified name to validate
	 * @param fieldName The field name for error messages
	 * @throws IllegalArgumentException if the format is invalid
	 */
	public static void validateQualifiedNameFormat(String qualifiedName, String fieldName) {
		ValidateArgument.required(qualifiedName, fieldName);
		if (!QUALIFIED_NAME_PATTERN.matcher(qualifiedName).matches()) {
			throw new IllegalArgumentException(
					"Invalid qualified name format for '" + fieldName + "': '" + qualifiedName
							+ "'. Expected format: '{organizationName}-{resourceName}'"
							+ " (e.g., 'org.sagebionetworks-SCIENTIFIC').");
		}
	}

	private SearchResourceConstants() {
		// Utility class
	}
}
