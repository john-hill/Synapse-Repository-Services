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
	 * Matches any JSON key ending in {@code _path} (e.g. {@code "stopwords_path"},
	 * {@code "synonyms_path"}, {@code "mappings_path"}, {@code "protected_words_path"},
	 * {@code "rules_path"}, {@code "word_list_path"}, {@code "hyphenation_patterns_path"}).
	 * All OpenSearch analysis parameters of this shape point to files on the cluster
	 * filesystem — unsupported in Amazon OpenSearch Serverless. Forward-compatible: any
	 * future {@code *_path} parameter is rejected automatically.
	 */
	private static final Pattern FILE_PATH_KEY = Pattern.compile("\"([a-zA-Z_]+_path)\"\\s*:");

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

	/**
	 * Reject OpenSearch analysis JSON that uses file-based parameters
	 * (any key ending in {@code _path} — see {@link #FILE_PATH_KEY}). Amazon OpenSearch
	 * Serverless does not support file uploads or custom packages, so these parameters
	 * fail at index-build time with a confusing AOSS error. Catching them at create/update
	 * time gives the user a clear remediation message pointing at the inline equivalent.
	 *
	 * @param json The OpenSearch component definition or full filter definition as a JSON string
	 * @param fieldName The schema field name for the error message
	 * @throws IllegalArgumentException if any {@code *_path} key appears in the JSON
	 */
	public static void rejectFilePathParameters(String json, String fieldName) {
		if (json == null || json.isEmpty()) {
			return;
		}
		java.util.regex.Matcher m = FILE_PATH_KEY.matcher(json);
		if (m.find()) {
			throw new IllegalArgumentException(
					"Amazon OpenSearch Serverless does not support file-based parameters."
							+ " The '" + m.group(1) + "' parameter in '" + fieldName + "' is not allowed."
							+ " Use the inline equivalent (e.g. 'stopwords', 'synonyms', 'mappings',"
							+ " 'protected_words') instead.");
		}
	}

	private SearchResourceConstants() {
		// Utility class
	}
}
