package org.sagebionetworks.repo.manager.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.sagebionetworks.repo.model.table.ColumnType;

public class ColumnTypeToOpenSearchMappingTest {

	@Test
	public void testStringDefaultsToScientific() {
		assertEquals(TextAnalyzerBootstrapper.SCIENTIFIC_ID,
			ColumnTypeToOpenSearchMapping.getDefaultAnalyzerId(ColumnType.STRING));
	}

	@Test
	public void testLinkDefaultsToKeyword() {
		assertEquals(TextAnalyzerBootstrapper.KEYWORD_ID,
			ColumnTypeToOpenSearchMapping.getDefaultAnalyzerId(ColumnType.LINK));
	}

	@Test
	public void testEntityIdIsKeywordType() {
		assertTrue(ColumnTypeToOpenSearchMapping.isKeywordType(ColumnType.ENTITYID));
		assertFalse(ColumnTypeToOpenSearchMapping.isTextType(ColumnType.ENTITYID));
	}

	@Test
	public void testStringIsTextType() {
		assertTrue(ColumnTypeToOpenSearchMapping.isTextType(ColumnType.STRING));
		assertFalse(ColumnTypeToOpenSearchMapping.isKeywordType(ColumnType.STRING));
	}

	@Test
	public void testIntegerIsLongType() {
		assertTrue(ColumnTypeToOpenSearchMapping.isLongType(ColumnType.INTEGER));
	}

	@Test
	public void testDoubleIsDoubleType() {
		assertTrue(ColumnTypeToOpenSearchMapping.isDoubleType(ColumnType.DOUBLE));
		assertFalse(ColumnTypeToOpenSearchMapping.isLongType(ColumnType.DOUBLE));
	}

	@Test
	public void testBooleanIsBooleanType() {
		assertTrue(ColumnTypeToOpenSearchMapping.isBooleanType(ColumnType.BOOLEAN));
	}

	@Test
	public void testJsonIsJsonType() {
		assertTrue(ColumnTypeToOpenSearchMapping.isJsonType(ColumnType.JSON));
	}

	@Test
	public void testIgnoreAboveValues() {
		assertEquals(Integer.valueOf(1000), ColumnTypeToOpenSearchMapping.getIgnoreAbove(ColumnType.STRING));
		assertEquals(Integer.valueOf(2000), ColumnTypeToOpenSearchMapping.getIgnoreAbove(ColumnType.MEDIUMTEXT));
		assertEquals(Integer.valueOf(8192), ColumnTypeToOpenSearchMapping.getIgnoreAbove(ColumnType.LARGETEXT));
		assertEquals(Integer.valueOf(256), ColumnTypeToOpenSearchMapping.getIgnoreAbove(ColumnType.ENTITYID));
	}

	@Test
	public void testAllColumnTypesHaveDefaultAnalyzer() {
		for (ColumnType type : ColumnType.values()) {
			assertNotNull(ColumnTypeToOpenSearchMapping.getDefaultAnalyzerId(type),
				"Expected non-null default analyzer ID for " + type);
		}
	}

	@ParameterizedTest(name = "{0} ignoreAbove = {1}")
	@MethodSource("ignoreAboveProvider")
	void testIgnoreAboveParameterized(ColumnType type, Integer expected) {
		assertEquals(expected, ColumnTypeToOpenSearchMapping.getIgnoreAbove(type));
	}

	static Stream<Arguments> ignoreAboveProvider() {
		return Stream.of(
			Arguments.of(ColumnType.STRING, 1000),
			Arguments.of(ColumnType.STRING_LIST, 1000),
			Arguments.of(ColumnType.MEDIUMTEXT, 2000),
			Arguments.of(ColumnType.LARGETEXT, 8192),
			Arguments.of(ColumnType.ENTITYID, 256),
			Arguments.of(ColumnType.USERID, 256),
			Arguments.of(ColumnType.LINK, 1000)
		);
	}

	@Test
	void testIgnoreAboveReturnsNullForNumericTypes() {
		assertNull(ColumnTypeToOpenSearchMapping.getIgnoreAbove(ColumnType.INTEGER));
		assertNull(ColumnTypeToOpenSearchMapping.getIgnoreAbove(ColumnType.DOUBLE));
	}

	@ParameterizedTest(name = "{0} is text type")
	@EnumSource(value = ColumnType.class, names = {"STRING", "STRING_LIST", "MEDIUMTEXT", "LARGETEXT"})
	void testTextTypes(ColumnType type) {
		assertTrue(ColumnTypeToOpenSearchMapping.isTextType(type));
	}

	@ParameterizedTest(name = "{0} is keyword type")
	@EnumSource(value = ColumnType.class, names = {"ENTITYID", "USERID", "ENTITYID_LIST", "USERID_LIST"})
	void testKeywordTypes(ColumnType type) {
		assertTrue(ColumnTypeToOpenSearchMapping.isKeywordType(type));
	}

	@ParameterizedTest(name = "{0} is long type")
	@EnumSource(value = ColumnType.class, names = {"INTEGER", "DATE", "INTEGER_LIST", "DATE_LIST", "FILEHANDLEID", "SUBMISSIONID", "EVALUATIONID"})
	void testLongTypes(ColumnType type) {
		assertTrue(ColumnTypeToOpenSearchMapping.isLongType(type));
	}

	@ParameterizedTest(name = "Every ColumnType has a default analyzer")
	@EnumSource(ColumnType.class)
	void testEveryColumnTypeHasDefaultAnalyzer(ColumnType type) {
		assertNotNull(ColumnTypeToOpenSearchMapping.getDefaultAnalyzerId(type));
	}

	@ParameterizedTest(name = "Every ColumnType has a default analyzer qualified name")
	@EnumSource(ColumnType.class)
	void testEveryColumnTypeHasDefaultAnalyzerQualifiedName(ColumnType type) {
		String qualifiedName = ColumnTypeToOpenSearchMapping.getDefaultAnalyzerQualifiedName(type);
		assertNotNull(qualifiedName, "Expected non-null qualified name for " + type);
		assertTrue(qualifiedName.contains("-"), "Qualified name should contain a dash separator: " + qualifiedName);
	}

	@Test
	void testGetDefaultAnalyzerQualifiedNameWithStringType() {
		assertEquals("org.sagebionetworks-SCIENTIFIC",
				ColumnTypeToOpenSearchMapping.getDefaultAnalyzerQualifiedName(ColumnType.STRING));
	}

	@Test
	void testGetDefaultAnalyzerQualifiedNameWithLinkType() {
		assertEquals("org.sagebionetworks-KEYWORD",
				ColumnTypeToOpenSearchMapping.getDefaultAnalyzerQualifiedName(ColumnType.LINK));
	}

	@Test
	void testGetDefaultAnalyzerQualifiedNameWithJsonType() {
		assertEquals("org.sagebionetworks-STANDARD",
				ColumnTypeToOpenSearchMapping.getDefaultAnalyzerQualifiedName(ColumnType.JSON));
	}

	@Test
	void testQualifiedNameAndIdMapParityWithAllTypes() {
		// Verify that every column type maps to consistent ID and qualified name
		for (ColumnType type : ColumnType.values()) {
			Long id = ColumnTypeToOpenSearchMapping.getDefaultAnalyzerId(type);
			String qualifiedName = ColumnTypeToOpenSearchMapping.getDefaultAnalyzerQualifiedName(type);
			assertNotNull(id, "Missing ID mapping for " + type);
			assertNotNull(qualifiedName, "Missing qualified name mapping for " + type);
			// Verify the qualified name ends with the expected analyzer name for the given ID
			if (id.equals(TextAnalyzerBootstrapper.SCIENTIFIC_ID)) {
				assertTrue(qualifiedName.endsWith("-SCIENTIFIC"), type + " should map to SCIENTIFIC");
			} else if (id.equals(TextAnalyzerBootstrapper.KEYWORD_ID)) {
				assertTrue(qualifiedName.endsWith("-KEYWORD"), type + " should map to KEYWORD");
			} else if (id.equals(TextAnalyzerBootstrapper.STANDARD_ID)) {
				assertTrue(qualifiedName.endsWith("-STANDARD"), type + " should map to STANDARD");
			}
		}
	}
}
