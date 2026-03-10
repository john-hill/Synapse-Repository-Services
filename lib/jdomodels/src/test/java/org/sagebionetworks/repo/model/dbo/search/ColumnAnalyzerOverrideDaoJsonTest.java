package org.sagebionetworks.repo.model.dbo.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.sagebionetworks.repo.model.table.search.ColumnAnalyzerOverrideEntry;

/**
 * Tests for JSON serialization/deserialization of ColumnAnalyzerOverrideEntry lists.
 * Tests the static methods on ColumnAnalyzerOverrideDaoImpl directly.
 */
public class ColumnAnalyzerOverrideDaoJsonTest {

	@Test
	void testOverridesToJsonAndBack() {
		ColumnAnalyzerOverrideEntry entry = new ColumnAnalyzerOverrideEntry();
		entry.setColumnName("diseaseFocus");
		entry.setIndexAnalyzerId("4");
		entry.setSearchAnalyzerId("4");

		String json = ColumnAnalyzerOverrideDaoImpl.overridesToJson(Arrays.asList(entry));
		assertNotNull(json);
		assertFalse(json.isEmpty());

		List<ColumnAnalyzerOverrideEntry> deserialized = ColumnAnalyzerOverrideDaoImpl.overridesFromJson(json);
		assertEquals(1, deserialized.size());
		assertEquals("diseaseFocus", deserialized.get(0).getColumnName());
		assertEquals("4", deserialized.get(0).getIndexAnalyzerId());
		assertEquals("4", deserialized.get(0).getSearchAnalyzerId());
	}

	@Test
	void testNullOverridesReturnsEmptyJsonArray() {
		String json = ColumnAnalyzerOverrideDaoImpl.overridesToJson(null);
		assertEquals("[]", json);
	}

	@Test
	void testEmptyOverridesReturnsEmptyJsonArray() {
		String json = ColumnAnalyzerOverrideDaoImpl.overridesToJson(Collections.emptyList());
		assertEquals("[]", json);
	}

	@Test
	void testOverridesFromNullJsonReturnsEmptyList() {
		List<ColumnAnalyzerOverrideEntry> result = ColumnAnalyzerOverrideDaoImpl.overridesFromJson(null);
		assertNotNull(result);
		assertTrue(result.isEmpty());
	}

	@Test
	void testOverridesFromEmptyStringReturnsEmptyList() {
		List<ColumnAnalyzerOverrideEntry> result = ColumnAnalyzerOverrideDaoImpl.overridesFromJson("");
		assertNotNull(result);
		assertTrue(result.isEmpty());
	}

	@Test
	void testMultipleOverridesRoundTrip() {
		ColumnAnalyzerOverrideEntry e1 = new ColumnAnalyzerOverrideEntry();
		e1.setColumnName("diseaseFocus");
		e1.setIndexAnalyzerId("4");
		e1.setSearchAnalyzerId("4");

		ColumnAnalyzerOverrideEntry e2 = new ColumnAnalyzerOverrideEntry();
		e2.setColumnName("doi");
		e2.setIndexAnalyzerId("3");
		e2.setSearchAnalyzerId("3");

		String json = ColumnAnalyzerOverrideDaoImpl.overridesToJson(Arrays.asList(e1, e2));
		List<ColumnAnalyzerOverrideEntry> deserialized = ColumnAnalyzerOverrideDaoImpl.overridesFromJson(json);

		assertEquals(2, deserialized.size());
		assertEquals("diseaseFocus", deserialized.get(0).getColumnName());
		assertEquals("doi", deserialized.get(1).getColumnName());
	}

	@Test
	void testOverridesPreserveOrder() {
		ColumnAnalyzerOverrideEntry first = new ColumnAnalyzerOverrideEntry();
		first.setColumnName("alpha");
		first.setIndexAnalyzerId("1");
		first.setSearchAnalyzerId("2");

		ColumnAnalyzerOverrideEntry second = new ColumnAnalyzerOverrideEntry();
		second.setColumnName("beta");
		second.setIndexAnalyzerId("3");
		second.setSearchAnalyzerId("4");

		ColumnAnalyzerOverrideEntry third = new ColumnAnalyzerOverrideEntry();
		third.setColumnName("gamma");
		third.setIndexAnalyzerId("5");
		third.setSearchAnalyzerId("6");

		String json = ColumnAnalyzerOverrideDaoImpl.overridesToJson(Arrays.asList(first, second, third));
		List<ColumnAnalyzerOverrideEntry> deserialized = ColumnAnalyzerOverrideDaoImpl.overridesFromJson(json);

		assertEquals("alpha", deserialized.get(0).getColumnName());
		assertEquals("beta", deserialized.get(1).getColumnName());
		assertEquals("gamma", deserialized.get(2).getColumnName());
	}

	@Test
	void testJsonWithUnknownFieldsIsForwardCompatible() {
		// Simulate JSON produced by a future version with an extra field
		String json = "[{\"columnName\":\"col1\",\"indexAnalyzerId\":\"1\",\"searchAnalyzerId\":\"2\",\"futureField\":\"ignored\"}]";

		List<ColumnAnalyzerOverrideEntry> result = ColumnAnalyzerOverrideDaoImpl.overridesFromJson(json);
		assertEquals(1, result.size());
		assertEquals("col1", result.get(0).getColumnName());
		assertEquals("1", result.get(0).getIndexAnalyzerId());
		assertEquals("2", result.get(0).getSearchAnalyzerId());
	}

	@Test
	void testOverridesWithSpecialCharactersInColumnName() {
		ColumnAnalyzerOverrideEntry entry = new ColumnAnalyzerOverrideEntry();
		entry.setColumnName("column \"with\" quotes, commas\nand newlines");
		entry.setIndexAnalyzerId("1");
		entry.setSearchAnalyzerId("2");

		String json = ColumnAnalyzerOverrideDaoImpl.overridesToJson(Arrays.asList(entry));
		List<ColumnAnalyzerOverrideEntry> deserialized = ColumnAnalyzerOverrideDaoImpl.overridesFromJson(json);

		assertEquals(1, deserialized.size());
		assertEquals("column \"with\" quotes, commas\nand newlines", deserialized.get(0).getColumnName());
	}
}
