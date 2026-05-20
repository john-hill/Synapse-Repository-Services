package org.sagebionetworks.repo.manager.search;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Utility for parsing the opaque-JSON {@code settings} blob of a TextAnalyzer (and the
 * {@code definition} blob of a SynonymSet), collecting any {@code {"$ref": "{org}-{name}"}}
 * entries inside, and resolving them at index-build time by substituting the referenced
 * SynonymSet's definition in place.
 *
 * <p>Synapse only verifies (a) that the JSON parses and (b) that any refs resolve. AOSS
 * validates the analyzer / token-filter shape itself at index-build time, so this class
 * does NOT validate component {@code type} enums or reject {@code *_path} parameters.</p>
 */
public final class SearchAnalyzerJson {

	static final String REF_KEY = "$ref";

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
	 * Walk {@code root} and collect every qualified-name appearing as a {@code $ref}
	 * value (a node whose only field is {@code "$ref"}). Returns a deduplicated, ordered
	 * set in walk order.
	 */
	public static Set<String> collectRefs(JsonNode root) {
		Set<String> refs = new LinkedHashSet<>();
		if (root == null) {
			return refs;
		}
		collectRefsRecursive(root, refs);
		return refs;
	}

	private static void collectRefsRecursive(JsonNode node, Set<String> refs) {
		if (node == null) {
			return;
		}
		String ref = readRef(node);
		if (ref != null) {
			refs.add(ref);
			return;
		}
		if (node.isObject()) {
			node.fields().forEachRemaining(e -> collectRefsRecursive(e.getValue(), refs));
		} else if (node.isArray()) {
			node.forEach(child -> collectRefsRecursive(child, refs));
		}
	}

	/**
	 * Depth-first walk; whenever a node is {@code {"$ref": "<qname>"}}, replace it in
	 * place with the JSON returned by {@code resolver.apply(qname)}. Cycles are detected
	 * via a visited stack and rejected with {@link IllegalArgumentException}. A null
	 * resolver result is treated as a missing target and reported. Errors include a
	 * JSON-pointer style breadcrumb pointing at the offending {@code $ref} location so
	 * the curator can locate it inside their settings tree.
	 *
	 * @param root     The JSON node to resolve. Mutated in place; returned for fluency.
	 * @param resolver Looks up a qname and returns its JSON definition, or {@code null}
	 *                 if the target does not exist.
	 * @return {@code root} with all refs replaced.
	 */
	public static JsonNode resolveRefs(JsonNode root, Function<String, JsonNode> resolver) {
		if (root == null) {
			return null;
		}
		Deque<String> visiting = new ArrayDeque<>();
		Deque<String> jsonPath = new ArrayDeque<>();
		return resolveNode(root, resolver, visiting, jsonPath);
	}

	private static JsonNode resolveNode(JsonNode node, Function<String, JsonNode> resolver,
			Deque<String> visiting, Deque<String> jsonPath) {
		if (node == null) {
			return null;
		}
		String ref = readRef(node);
		if (ref != null) {
			if (visiting.contains(ref)) {
				throw new IllegalArgumentException(
						"Circular $ref detected: '" + ref + "' at " + renderJsonPointer(jsonPath)
								+ " (cycle: " + visiting + ")");
			}
			JsonNode target = resolver.apply(ref);
			if (target == null) {
				throw new IllegalArgumentException("Unresolved $ref: '" + ref + "' at "
						+ renderJsonPointer(jsonPath));
			}
			visiting.push(ref);
			try {
				return resolveNode(target, resolver, visiting, jsonPath);
			} finally {
				visiting.pop();
			}
		}
		if (node.isObject()) {
			ObjectNode obj = (ObjectNode) node;
			java.util.Iterator<Map.Entry<String, JsonNode>> it = obj.fields();
			java.util.List<Map.Entry<String, JsonNode>> entries = new java.util.ArrayList<>();
			while (it.hasNext()) {
				entries.add(it.next());
			}
			for (Map.Entry<String, JsonNode> e : entries) {
				jsonPath.addLast(escapeJsonPointerToken(e.getKey()));
				try {
					JsonNode resolved = resolveNode(e.getValue(), resolver, visiting, jsonPath);
					if (resolved != e.getValue()) {
						obj.set(e.getKey(), resolved);
					}
				} finally {
					jsonPath.removeLast();
				}
			}
			return obj;
		}
		if (node.isArray()) {
			com.fasterxml.jackson.databind.node.ArrayNode arr =
					(com.fasterxml.jackson.databind.node.ArrayNode) node;
			for (int i = 0; i < arr.size(); i++) {
				jsonPath.addLast(Integer.toString(i));
				try {
					JsonNode resolved = resolveNode(arr.get(i), resolver, visiting, jsonPath);
					if (resolved != arr.get(i)) {
						arr.set(i, resolved);
					}
				} finally {
					jsonPath.removeLast();
				}
			}
			return arr;
		}
		return node;
	}

	/**
	 * Render the breadcrumb deque as a JSON Pointer (RFC 6901). Empty deque renders as
	 * the root pointer {@code ""}; otherwise tokens are joined with {@code /} prefixes,
	 * matching standard Jackson {@code at(...)} input.
	 */
	private static String renderJsonPointer(Deque<String> jsonPath) {
		if (jsonPath.isEmpty()) {
			return "/";
		}
		StringBuilder sb = new StringBuilder();
		for (String token : jsonPath) {
			sb.append('/').append(token);
		}
		return sb.toString();
	}

	/**
	 * Escape an object field name for inclusion in a JSON Pointer per RFC 6901: {@code ~}
	 * → {@code ~0} and {@code /} → {@code ~1}, in that order.
	 */
	private static String escapeJsonPointerToken(String fieldName) {
		return fieldName.replace("~", "~0").replace("/", "~1");
	}

	/**
	 * If {@code node} is an object whose only field is {@code "$ref"} with a textual
	 * value, return that value; otherwise null.
	 */
	private static String readRef(JsonNode node) {
		if (node == null || !node.isObject()) {
			return null;
		}
		if (node.size() != 1) {
			return null;
		}
		JsonNode ref = node.get(REF_KEY);
		if (ref == null || !ref.isTextual()) {
			return null;
		}
		return ref.asText();
	}
}
