package org.sagebionetworks.repo.manager.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class SearchDslSanitizerTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	// ---------- scanForbiddenKeys ----------

	@Test
	public void testScanForbiddenKeysWithScriptRejected() throws Exception {
		// A script hidden inside an opaque leaf (e.g. a term value object) is rejected wherever it
		// appears in a surface.
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslSanitizer.scanForbiddenKeys(
						MAPPER.readTree("{\"term\":{\"f\":{\"value\":{\"script\":{}}}}}"), "query"));
		assertTrue(ex.getMessage().contains("script"));
	}

	@Test
	public void testScanForbiddenKeysWithIndexedShapeRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslSanitizer.scanForbiddenKeys(
						MAPPER.readTree("{\"a\":{\"indexed_shape\":{}}}"), "query"));
		assertTrue(ex.getMessage().contains("indexed_shape"));
	}

	@Test
	public void testScanForbiddenKeysWithRuntimeMappingsRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslSanitizer.scanForbiddenKeys(
						MAPPER.readTree("{\"runtime_mappings\":{}}"), "query"));
		assertTrue(ex.getMessage().contains("runtime_mappings"));
	}

	@Test
	public void testScanForbiddenKeysWithScriptFieldsRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslSanitizer.scanForbiddenKeys(
						MAPPER.readTree("{\"script_fields\":{}}"), "query"));
		assertTrue(ex.getMessage().contains("script_fields"));
	}

	@Test
	public void testScanForbiddenKeysWithSearchTemplateRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslSanitizer.scanForbiddenKeys(
						MAPPER.readTree("{\"_search_template\":{}}"), "query"));
		assertTrue(ex.getMessage().contains("_search_template"));
	}

	@Test
	public void testScanForbiddenKeysWithForbiddenKeyInsideArrayRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslSanitizer.scanForbiddenKeys(
						MAPPER.readTree("{\"must\":[{\"match_all\":{}},{\"script\":{}}]}"), "query"));
		assertTrue(ex.getMessage().contains("script"));
	}

	@Test
	public void testScanForbiddenKeysWithDepthOverLimitRejected() throws Exception {
		StringBuilder open = new StringBuilder();
		StringBuilder close = new StringBuilder();
		for (int i = 0; i <= SearchDslSanitizer.FORBIDDEN_SCAN_MAX_DEPTH + 5; i++) {
			open.append("{\"a\":");
			close.append("}");
		}
		open.append("1").append(close);
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslSanitizer.scanForbiddenKeys(MAPPER.readTree(open.toString()), "query"));
		assertTrue(ex.getMessage().contains("too deeply"));
	}

	@Test
	public void testScanForbiddenKeysWithBenignTreePasses() throws Exception {
		// A tree with no forbidden keys passes (no exception). Nested objects and arrays are walked.
		SearchDslSanitizer.scanForbiddenKeys(
				MAPPER.readTree("{\"bool\":{\"must\":[{\"match\":{\"f\":{\"query\":\"x\"}}}]}}"), "query");
	}

	// ---------- sanitizeBodyTopLevel ----------

	@Test
	public void testSanitizeBodyTopLevelWithDisallowedKeyRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslSanitizer.sanitizeBodyTopLevel(
						MAPPER.readTree("{\"query\":{\"match_all\":{}},\"bogus\":1}")));
		assertTrue(ex.getMessage().contains("bogus"));
	}

	@Test
	public void testSanitizeAutocompleteBodyTopLevelWithDisallowedKeyRejected() throws Exception {
		// Autocomplete narrows the body to query + _source; aggregations is not allowed.
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslSanitizer.sanitizeAutocompleteBodyTopLevel(
						MAPPER.readTree("{\"query\":{\"prefix\":{\"f\":{\"value\":\"x\"}}},\"aggregations\":{}}")));
		assertTrue(ex.getMessage().contains("aggregations"));
	}

	// ---------- sanitizeSource ----------

	@Test
	public void testSanitizeSourceWithUnknownObjectKeyRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslSanitizer.sanitizeSource(MAPPER.readTree(
						"{\"includes\":[\"a\"],\"bogus\":1}")));
		assertTrue(ex.getMessage().contains("bogus"));
	}

	@Test
	public void testSanitizeSourceWithBooleanRoundTrips() throws Exception {
		JsonNode in = MAPPER.readTree("false");
		// call under test
		assertEquals(in, SearchDslSanitizer.sanitizeSource(in));
	}

	@Test
	public void testSanitizeSourceWithIncludesExcludesRoundTrips() throws Exception {
		JsonNode in = MAPPER.readTree("{\"includes\":[\"a\"],\"excludes\":[\"b\"]}");
		// call under test
		assertEquals(in, SearchDslSanitizer.sanitizeSource(in));
	}

	// ---------- sanitizeSort ----------

	@Test
	public void testSanitizeSortWithUnknownOptionKeyRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslSanitizer.sanitizeSort(MAPPER.readTree(
						"[{\"title\":{\"order\":\"asc\",\"bogus\":1}}]")));
		assertTrue(ex.getMessage().contains("bogus"));
	}

	@Test
	public void testSanitizeSortWithScriptSortRejected() throws Exception {
		// _script sorts run Painless and are not supported; rejected, not silently dropped.
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslSanitizer.sanitizeSort(MAPPER.readTree(
						"[{\"_script\":{\"type\":\"number\",\"script\":\"x\"}}]")));
		assertTrue(ex.getMessage().contains("_script"));
	}

	@Test
	public void testSanitizeSortWithGeoDistanceSortRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslSanitizer.sanitizeSort(MAPPER.readTree(
						"[{\"_geo_distance\":{\"loc\":[0,0]}}]")));
		assertTrue(ex.getMessage().contains("_geo_distance"));
	}

	@Test
	public void testSanitizeSortWithScoreAccepted() throws Exception {
		JsonNode in = MAPPER.readTree("{\"_score\":{\"order\":\"desc\"}}");
		// call under test
		assertEquals(in, SearchDslSanitizer.sanitizeSort(in));
	}

	@Test
	public void testSanitizeSortWithInvalidElementRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslSanitizer.sanitizeSort(MAPPER.readTree("[123]")));
		assertTrue(ex.getMessage().contains("field name or a sort options object"));
	}

	@Test
	public void testSanitizeSortRoundTripsUnchanged() throws Exception {
		JsonNode in = MAPPER.readTree("[\"title\",{\"year\":{\"order\":\"desc\"}},\"_score\"]");
		// call under test
		assertEquals(in, SearchDslSanitizer.sanitizeSort(in));
	}

	// ---------- copyAllowlistedKeys ----------

	@Test
	public void testCopyAllowlistedKeysWithAllowedKeys() throws Exception {
		JsonNode in = MAPPER.readTree("{\"a\":1,\"b\":2}");
		// call under test
		assertEquals(in, SearchDslSanitizer.copyAllowlistedKeys(in, Set.of("a", "b"), "surface", "p"));
	}

	@Test
	public void testCopyAllowlistedKeysWithDisallowedKeyRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslSanitizer.copyAllowlistedKeys(MAPPER.readTree("{\"a\":1,\"c\":3}"),
						Set.of("a"), "surface", "p"));
		assertTrue(ex.getMessage().contains("c"));
	}

	@Test
	public void testCopyAllowlistedKeysWithForbiddenKeyInOpaqueValueRejected() throws Exception {
		// copyAllowlistedKeys deep-copies each allowed value through the forbidden-key scan, so a
		// script hidden inside an allowed opaque value is still rejected.
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslSanitizer.copyAllowlistedKeys(
						MAPPER.readTree("{\"a\":{\"script\":{}}}"), Set.of("a"), "surface", "p"));
		assertTrue(ex.getMessage().contains("script"));
	}

	// ---------- copyOpaque ----------

	@Test
	public void testCopyOpaqueWithForbiddenKeyRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslSanitizer.copyOpaque(MAPPER.readTree("{\"script\":{}}"), "_source"));
		assertTrue(ex.getMessage().contains("script"));
	}

	@Test
	public void testCopyOpaqueWithNestedObjectAndArrayRoundTrips() throws Exception {
		JsonNode in = MAPPER.readTree("{\"a\":[1,2,{\"b\":\"c\"}]}");
		// call under test
		assertEquals(in, SearchDslSanitizer.copyOpaque(in, "_source"));
	}

	// ---------- requireObject ----------

	@Test
	public void testRequireObjectWithNullRejected() {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslSanitizer.requireObject(null, "query"));
		assertTrue(ex.getMessage().contains("expected a JSON object"));
	}

	@Test
	public void testRequireObjectWithNonObjectRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslSanitizer.requireObject(MAPPER.readTree("[]"), "query"));
		assertTrue(ex.getMessage().contains("expected a JSON object"));
	}
}
