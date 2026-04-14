package org.sagebionetworks.repo.manager.search;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.sagebionetworks.repo.manager.schema.SynapseSchemaBootstrapImpl;
import org.sagebionetworks.repo.model.table.ColumnConstants;
import org.sagebionetworks.repo.model.table.ColumnType;

/**
 * Utility class that maps Synapse {@link ColumnType} to OpenSearch field mapping characteristics.
 * This drives index creation: determining the primary OS type, sub-fields, analyzer IDs,
 * and ignoreAbove limits for keyword sub-fields.
 *
 * <p>Mapping summary:
 * <ul>
 *   <li>STRING, STRING_LIST, MEDIUMTEXT, LARGETEXT: text + .keyword sub-field</li>
 *   <li>LINK: depends on effective analyzer (KEYWORD analyzer: keyword + .searchable; other: text + .keyword)</li>
 *   <li>INTEGER, DATE, INTEGER_LIST, DATE_LIST, FILEHANDLEID, SUBMISSIONID, EVALUATIONID: long</li>
 *   <li>ENTITYID, USERID, ENTITYID_LIST, USERID_LIST: keyword (ignoreAbove=256)</li>
 *   <li>DOUBLE: double</li>
 *   <li>BOOLEAN, BOOLEAN_LIST: boolean</li>
 *   <li>JSON: object (dynamic:true)</li>
 * </ul>
 */
public final class ColumnTypeToOpenSearchMapping {

	private ColumnTypeToOpenSearchMapping() {
		// Utility class, not instantiable
	}

	/**
	 * Column types that map to OpenSearch text (full-text searchable).
	 */
	private static final Set<ColumnType> TEXT_TYPES = EnumSet.of(
			ColumnType.STRING,
			ColumnType.STRING_LIST,
			ColumnType.MEDIUMTEXT,
			ColumnType.LARGETEXT
	);

	/**
	 * Column types that map to OpenSearch long.
	 */
	private static final Set<ColumnType> LONG_TYPES = EnumSet.of(
			ColumnType.INTEGER,
			ColumnType.DATE,
			ColumnType.INTEGER_LIST,
			ColumnType.DATE_LIST,
			ColumnType.FILEHANDLEID,
			ColumnType.SUBMISSIONID,
			ColumnType.EVALUATIONID
	);

	/**
	 * Column types that map to OpenSearch keyword (exact match).
	 */
	private static final Set<ColumnType> KEYWORD_TYPES = EnumSet.of(
			ColumnType.ENTITYID,
			ColumnType.USERID,
			ColumnType.ENTITYID_LIST,
			ColumnType.USERID_LIST
	);

	/**
	 * Column types that map to OpenSearch boolean.
	 */
	private static final Set<ColumnType> BOOLEAN_TYPES = EnumSet.of(
			ColumnType.BOOLEAN,
			ColumnType.BOOLEAN_LIST
	);

	/**
	 * Numeric column types (long + double).
	 */
	private static final Set<ColumnType> NUMERIC_TYPES = EnumSet.of(
			ColumnType.INTEGER,
			ColumnType.DATE,
			ColumnType.INTEGER_LIST,
			ColumnType.DATE_LIST,
			ColumnType.FILEHANDLEID,
			ColumnType.SUBMISSIONID,
			ColumnType.EVALUATIONID,
			ColumnType.DOUBLE
	);

	private static final String ORG = SynapseSchemaBootstrapImpl.ORG_SAGEBIONETWORKS;
	private static final String SCIENTIFIC_QUALIFIED = ORG + "-SCIENTIFIC";
	private static final String KEYWORD_QUALIFIED = ORG + "-KEYWORD";
	private static final String STANDARD_QUALIFIED = ORG + "-STANDARD";

	/**
	 * Default analyzer IDs per column type, referencing TextAnalyzerBootstrapper constants.
	 */
	private static final Map<ColumnType, Long> DEFAULT_ANALYZER_MAP;

	/**
	 * Default analyzer qualified names per column type. Parallel to DEFAULT_ANALYZER_MAP
	 * but uses the qualified name ({orgName}-{name}) format used by the REST API.
	 */
	private static final Map<ColumnType, String> DEFAULT_ANALYZER_QUALIFIED_NAME_MAP;
	static {
		DEFAULT_ANALYZER_MAP = new EnumMap<>(ColumnType.class);
		DEFAULT_ANALYZER_QUALIFIED_NAME_MAP = new EnumMap<>(ColumnType.class);
		// STRING types default to SCIENTIFIC
		DEFAULT_ANALYZER_MAP.put(ColumnType.STRING, TextAnalyzerBootstrapper.SCIENTIFIC_ID);
		DEFAULT_ANALYZER_MAP.put(ColumnType.STRING_LIST, TextAnalyzerBootstrapper.SCIENTIFIC_ID);
		DEFAULT_ANALYZER_MAP.put(ColumnType.MEDIUMTEXT, TextAnalyzerBootstrapper.SCIENTIFIC_ID);
		DEFAULT_ANALYZER_MAP.put(ColumnType.LARGETEXT, TextAnalyzerBootstrapper.SCIENTIFIC_ID);
		DEFAULT_ANALYZER_QUALIFIED_NAME_MAP.put(ColumnType.STRING, SCIENTIFIC_QUALIFIED);
		DEFAULT_ANALYZER_QUALIFIED_NAME_MAP.put(ColumnType.STRING_LIST, SCIENTIFIC_QUALIFIED);
		DEFAULT_ANALYZER_QUALIFIED_NAME_MAP.put(ColumnType.MEDIUMTEXT, SCIENTIFIC_QUALIFIED);
		DEFAULT_ANALYZER_QUALIFIED_NAME_MAP.put(ColumnType.LARGETEXT, SCIENTIFIC_QUALIFIED);
		// LINK defaults to KEYWORD
		DEFAULT_ANALYZER_MAP.put(ColumnType.LINK, TextAnalyzerBootstrapper.KEYWORD_ID);
		DEFAULT_ANALYZER_QUALIFIED_NAME_MAP.put(ColumnType.LINK, KEYWORD_QUALIFIED);
		// ID types default to KEYWORD
		DEFAULT_ANALYZER_MAP.put(ColumnType.ENTITYID, TextAnalyzerBootstrapper.KEYWORD_ID);
		DEFAULT_ANALYZER_MAP.put(ColumnType.USERID, TextAnalyzerBootstrapper.KEYWORD_ID);
		DEFAULT_ANALYZER_MAP.put(ColumnType.ENTITYID_LIST, TextAnalyzerBootstrapper.KEYWORD_ID);
		DEFAULT_ANALYZER_MAP.put(ColumnType.USERID_LIST, TextAnalyzerBootstrapper.KEYWORD_ID);
		DEFAULT_ANALYZER_MAP.put(ColumnType.FILEHANDLEID, TextAnalyzerBootstrapper.KEYWORD_ID);
		DEFAULT_ANALYZER_MAP.put(ColumnType.SUBMISSIONID, TextAnalyzerBootstrapper.KEYWORD_ID);
		DEFAULT_ANALYZER_MAP.put(ColumnType.EVALUATIONID, TextAnalyzerBootstrapper.KEYWORD_ID);
		DEFAULT_ANALYZER_QUALIFIED_NAME_MAP.put(ColumnType.ENTITYID, KEYWORD_QUALIFIED);
		DEFAULT_ANALYZER_QUALIFIED_NAME_MAP.put(ColumnType.USERID, KEYWORD_QUALIFIED);
		DEFAULT_ANALYZER_QUALIFIED_NAME_MAP.put(ColumnType.ENTITYID_LIST, KEYWORD_QUALIFIED);
		DEFAULT_ANALYZER_QUALIFIED_NAME_MAP.put(ColumnType.USERID_LIST, KEYWORD_QUALIFIED);
		DEFAULT_ANALYZER_QUALIFIED_NAME_MAP.put(ColumnType.FILEHANDLEID, KEYWORD_QUALIFIED);
		DEFAULT_ANALYZER_QUALIFIED_NAME_MAP.put(ColumnType.SUBMISSIONID, KEYWORD_QUALIFIED);
		DEFAULT_ANALYZER_QUALIFIED_NAME_MAP.put(ColumnType.EVALUATIONID, KEYWORD_QUALIFIED);
		// JSON defaults to STANDARD
		DEFAULT_ANALYZER_MAP.put(ColumnType.JSON, TextAnalyzerBootstrapper.STANDARD_ID);
		DEFAULT_ANALYZER_QUALIFIED_NAME_MAP.put(ColumnType.JSON, STANDARD_QUALIFIED);
		// Numeric and boolean types have no analyzer; mapped to KEYWORD as a safe default
		DEFAULT_ANALYZER_MAP.put(ColumnType.INTEGER, TextAnalyzerBootstrapper.KEYWORD_ID);
		DEFAULT_ANALYZER_MAP.put(ColumnType.INTEGER_LIST, TextAnalyzerBootstrapper.KEYWORD_ID);
		DEFAULT_ANALYZER_MAP.put(ColumnType.DOUBLE, TextAnalyzerBootstrapper.KEYWORD_ID);
		DEFAULT_ANALYZER_MAP.put(ColumnType.DATE, TextAnalyzerBootstrapper.KEYWORD_ID);
		DEFAULT_ANALYZER_MAP.put(ColumnType.DATE_LIST, TextAnalyzerBootstrapper.KEYWORD_ID);
		DEFAULT_ANALYZER_MAP.put(ColumnType.BOOLEAN, TextAnalyzerBootstrapper.KEYWORD_ID);
		DEFAULT_ANALYZER_MAP.put(ColumnType.BOOLEAN_LIST, TextAnalyzerBootstrapper.KEYWORD_ID);
		DEFAULT_ANALYZER_QUALIFIED_NAME_MAP.put(ColumnType.INTEGER, KEYWORD_QUALIFIED);
		DEFAULT_ANALYZER_QUALIFIED_NAME_MAP.put(ColumnType.INTEGER_LIST, KEYWORD_QUALIFIED);
		DEFAULT_ANALYZER_QUALIFIED_NAME_MAP.put(ColumnType.DOUBLE, KEYWORD_QUALIFIED);
		DEFAULT_ANALYZER_QUALIFIED_NAME_MAP.put(ColumnType.DATE, KEYWORD_QUALIFIED);
		DEFAULT_ANALYZER_QUALIFIED_NAME_MAP.put(ColumnType.DATE_LIST, KEYWORD_QUALIFIED);
		DEFAULT_ANALYZER_QUALIFIED_NAME_MAP.put(ColumnType.BOOLEAN, KEYWORD_QUALIFIED);
		DEFAULT_ANALYZER_QUALIFIED_NAME_MAP.put(ColumnType.BOOLEAN_LIST, KEYWORD_QUALIFIED);
	}

	/**
	 * OpenSearch ignoreAbove limit for large text keyword sub-fields.
	 * Distinct from {@link ColumnConstants#MAX_LARGE_TEXT_CHARACTERS} which is the Synapse storage limit.
	 */
	private static final int LARGE_TEXT_IGNORE_ABOVE = 8192;

	/**
	 * OpenSearch ignoreAbove limit for ID-type keyword fields (entity IDs, user IDs).
	 */
	private static final int ID_KEYWORD_IGNORE_ABOVE = 256;

	/**
	 * ignoreAbove values for keyword sub-fields, keyed by column type.
	 */
	private static final Map<ColumnType, Integer> IGNORE_ABOVE_MAP;
	static {
		IGNORE_ABOVE_MAP = new EnumMap<>(ColumnType.class);
		IGNORE_ABOVE_MAP.put(ColumnType.STRING, ColumnConstants.MAX_ALLOWED_STRING_SIZE.intValue());
		IGNORE_ABOVE_MAP.put(ColumnType.STRING_LIST, ColumnConstants.MAX_ALLOWED_STRING_SIZE.intValue());
		IGNORE_ABOVE_MAP.put(ColumnType.LINK, ColumnConstants.MAX_ALLOWED_STRING_SIZE.intValue());
		IGNORE_ABOVE_MAP.put(ColumnType.MEDIUMTEXT, (int) ColumnConstants.MAX_MEDIUM_TEXT_CHARACTERS);
		IGNORE_ABOVE_MAP.put(ColumnType.LARGETEXT, LARGE_TEXT_IGNORE_ABOVE);
		IGNORE_ABOVE_MAP.put(ColumnType.ENTITYID, ID_KEYWORD_IGNORE_ABOVE);
		IGNORE_ABOVE_MAP.put(ColumnType.USERID, ID_KEYWORD_IGNORE_ABOVE);
		IGNORE_ABOVE_MAP.put(ColumnType.ENTITYID_LIST, ID_KEYWORD_IGNORE_ABOVE);
		IGNORE_ABOVE_MAP.put(ColumnType.USERID_LIST, ID_KEYWORD_IGNORE_ABOVE);
	}

	public static boolean isTextType(ColumnType columnType) {
		return TEXT_TYPES.contains(columnType);
	}

	public static boolean isNumericType(ColumnType columnType) {
		return NUMERIC_TYPES.contains(columnType);
	}

	public static boolean isKeywordType(ColumnType columnType) {
		return KEYWORD_TYPES.contains(columnType);
	}

	public static boolean isBooleanType(ColumnType columnType) {
		return BOOLEAN_TYPES.contains(columnType);
	}

	public static boolean isLongType(ColumnType columnType) {
		return LONG_TYPES.contains(columnType);
	}

	public static boolean isDoubleType(ColumnType columnType) {
		return columnType == ColumnType.DOUBLE;
	}

	public static boolean isJsonType(ColumnType columnType) {
		return columnType == ColumnType.JSON;
	}

	public static boolean isLinkType(ColumnType columnType) {
		return columnType == ColumnType.LINK;
	}

	/**
	 * Returns the default text analyzer ID for the given column type.
	 * This is the analyzer used when no explicit override or configuration default is provided.
	 *
	 * @param columnType The Synapse column type
	 * @return The default analyzer ID, never null
	 */
	public static Long getDefaultAnalyzerId(ColumnType columnType) {
		Long id = DEFAULT_ANALYZER_MAP.get(columnType);
		return id != null ? id : TextAnalyzerBootstrapper.SCIENTIFIC_ID;
	}

	/**
	 * Returns the default text analyzer qualified name for the given column type.
	 * Uses the format {orgName}-{name} (e.g., "org.sagebionetworks-SCIENTIFIC").
	 *
	 * @param columnType The Synapse column type
	 * @return The default analyzer qualified name, never null
	 */
	public static String getDefaultAnalyzerQualifiedName(ColumnType columnType) {
		String name = DEFAULT_ANALYZER_QUALIFIED_NAME_MAP.get(columnType);
		return name != null ? name : SCIENTIFIC_QUALIFIED;
	}

	/**
	 * Returns the ignoreAbove value for keyword sub-fields associated with the given column type.
	 *
	 * @param columnType The Synapse column type
	 * @return The ignoreAbove value, or null if not applicable
	 */
	public static Integer getIgnoreAbove(ColumnType columnType) {
		return IGNORE_ABOVE_MAP.get(columnType);
	}
}
