package org.sagebionetworks.repo.manager.search;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Utility for parsing the opaque-JSON {@code settings} blob of a TextAnalyzer (and the
 * {@code definition} blob of a SynonymSet), collecting any {@code {"$ref": "{org}-{name}"}}
 * entries that appear inside the top-level {@code filter} registry map, and resolving them
 * at index-build time by substituting the referenced SynonymSet's definition in place.
 *
 * <p>Per the TextAnalyzer schema, a {@code $ref} is only permitted as a direct value of an
 * entry in the top-level {@code filter} map (it replaces an inline filter definition).
 * SynonymSet definitions are themselves single token-filter blobs and cannot contain
 * {@code $ref}, so resolution is a single non-recursive substitution pass.</p>
 *
 * <p>Synapse only verifies (a) that the JSON parses and (b) that any refs resolve. AOSS
 * validates the analyzer / token-filter shape itself at index-build time.</p>
 */
public final class SearchAnalyzerJson {

	static final String REF_KEY = "$ref";

	static final String FILTER_KEY = "filter";

	/**
	 * The required entry name inside a TextAnalyzer's inner {@code analyzer} map. Field
	 * mappings and {@code SearchConfiguration.defaultAnalyzer} resolve a bare qualified
	 * name to this entry; OpenSearch promotes it to the index's reserved
	 * {@code analysis.analyzer.default} slot when the TextAnalyzer is the configuration's
	 * primary.
	 */
	public static final String DEFAULT_ANALYZER_KEY = "default";

	/**
	 * The optional entry name inside a TextAnalyzer's inner {@code analyzer} map for
	 * asymmetric search analysis. When present alongside {@link #DEFAULT_ANALYZER_KEY},
	 * the configuration's primary TextAnalyzer also promotes it to the index's reserved
	 * {@code analysis.analyzer.default_search} slot.
	 */
	public static final String DEFAULT_SEARCH_ANALYZER_KEY = "default_search";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private SearchAnalyzerJson() {
		// utility
	}

	/**
	 * Parse a JSON-object string. Throws {@link IllegalArgumentException} with a
	 * user-facing message on malformed JSON.
	 */
	public static JsonNode parse(String json) {
		if (json == null) {
			throw new IllegalArgumentException("JSON string is required.");
		}
		try {
			return MAPPER.readTree(json);
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException("Invalid JSON: " + e.getOriginalMessage(), e);
		}
	}

	/**
	 * Collect every qualified-name appearing as a {@code $ref} value anywhere in
	 * {@code root}. Returns a deduplicated, ordered set in walk order.
	 */
	public static Set<String> collectRefs(JsonNode root) {
		if (root == null) {
			return Collections.emptySet();
		}
		Set<String> refs = new LinkedHashSet<>();
		for (JsonNode v : root.findValues(REF_KEY)) {
			if (v.isTextual()) {
				refs.add(v.asText());
			}
		}
		return refs;
	}

	/**
	 * Substitute every {@code {"$ref": "<qname>"}} entry in the top-level {@code filter}
	 * map with the JSON returned by {@code resolver.apply(qname)}. A null resolver result
	 * is treated as a missing target and reported with the offending {@code /filter/<name>}
	 * pointer so the curator can locate it.
	 *
	 * @param root     The JSON node to resolve. Mutated in place; returned for fluency.
	 * @param resolver Looks up a qname and returns its JSON definition, or {@code null}
	 *                 if the target does not exist.
	 * @return {@code root} with all refs in {@code filter} replaced.
	 */
	public static JsonNode resolveRefs(JsonNode root, Function<String, JsonNode> resolver) {
		if (root == null) {
			return null;
		}
		JsonNode filterMap = root.get(FILTER_KEY);
		if (filterMap == null || !filterMap.isObject()) {
			return root;
		}
		ObjectNode filterObj = (ObjectNode) filterMap;
		filterObj.fields().forEachRemaining(entry -> {
			String ref = readRef(entry.getValue());
			if (ref == null) {
				return;
			}
			JsonNode target = resolver.apply(ref);
			if (target == null) {
				throw new IllegalArgumentException(
						"Unresolved $ref: '" + ref + "' at /" + FILTER_KEY + "/" + entry.getKey());
			}
			filterObj.set(entry.getKey(), target);
		});
		return root;
	}

	/**
	 * If {@code node} is an object whose only field is {@code "$ref"} with a textual
	 * value, return that value; otherwise null.
	 */
	private static String readRef(JsonNode node) {
		if (node == null || !node.isObject() || node.size() != 1) {
			return null;
		}
		JsonNode ref = node.get(REF_KEY);
		return (ref != null && ref.isTextual()) ? ref.asText() : null;
	}
}
