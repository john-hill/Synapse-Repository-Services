package org.sagebionetworks.repo.manager.search;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The structural / security layer of the search-DSL gate, ahead of the typed resource caps in
 * {@link SearchDslValidator}.
 *
 * <p>Clause / aggregation / feature structure is enforced by the typed {@code SearchQuery} POJO:
 * unsupported clause kinds and properties are dropped on deserialization and rejected at the
 * request boundary. This class enforces the protections the typed model cannot:</p>
 * <ul>
 *   <li><b>Forbidden-key scan</b> ({@link #scanForbiddenKeys}). Several DSL leaves are opaque
 *       pass-through values (a {@code term.value}, a {@code highlight.options} map, an aggregation
 *       {@code order}, ...) where a {@code script} or other {@link #COPY_FORBIDDEN_KEYS} construct
 *       can hide. Each typed surface is scanned for those keys before it reaches OpenSearch.</li>
 *   <li><b>Opaque {@code sort} / {@code _source}</b> ({@link #sanitizeSort} / {@link #sanitizeSource}).
 *       These two top-level slots are multi-shape unions with no typed POJO, so they are rebuilt
 *       from an allowlist &mdash; which is also where script / geo sorts ({@code _script},
 *       {@code _geo_distance}) are rejected.</li>
 *   <li><b>Top-level key allowlist</b> ({@link #sanitizeBodyTopLevel} /
 *       {@link #sanitizeAutocompleteBodyTopLevel}).</li>
 * </ul>
 *
 * <p>This protects against <b>script injection</b> hidden in an opaque leaf, <b>cross-index reach</b>
 * via the {@code terms} lookup form (also rejected in {@link SearchDslValidator}), and script / geo
 * <b>sort</b> clauses. The numeric resource caps (depth, clause count, value-array length, prefix
 * expansion, histogram bucket bound, cardinality precision, leading-wildcard rejection) run
 * afterwards on the typed objects in {@link SearchDslValidator}.</p>
 *
 * <p>Throws {@link IllegalArgumentException} (HTTP 400) on any violation.</p>
 */
final class SearchDslSanitizer {

	private SearchDslSanitizer() {
	}

	/**
	 * Maximum recursion depth for {@link #scanForbiddenKeys} / {@link #copyOpaque}. An opaque
	 * options value can be arbitrarily nested JSON, and an unbounded recursive walk would blow the
	 * stack on a pathological input.
	 */
	static final int FORBIDDEN_SCAN_MAX_DEPTH = 100;

	/**
	 * Allowlisted top-level keys on {@code SearchQuery.body}. Anything else is rejected
	 * with HTTP 400.
	 */
	static final Set<String> BODY_ALLOWED_KEYS = Set.of(
			"query", "post_filter",
			"aggregations",
			"highlight", "collapse", "rescore",
			"sort", "_source",
			"from", "size", "search_after");

	/**
	 * Narrow allowlist for {@code SearchAutocompleteRequest.searchQuery}. The dropdown surface has
	 * no aggregations / sort / pagination — only a prefix-flavored {@code query}
	 * and an optional {@code _source} filter.
	 */
	static final Set<String> AUTOCOMPLETE_BODY_ALLOWED_KEYS = Set.of("query", "_source");

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	/**
	 * Keys forbidden anywhere inside a search-DSL surface. They are not legitimate keys anywhere in
	 * the allowed DSL, and could otherwise hide inside an opaque pass-through value (a
	 * {@code highlight.options} map, a leaf-query option value, ...), so {@link #scanForbiddenKeys}
	 * rejects them wherever they appear:
	 * {@code script} (Painless on any variant), {@code indexed_shape} (cross-index geo reference),
	 * {@code runtime_mappings} / {@code script_fields} (Painless), and {@code _search_template}.
	 */
	private static final Set<String> COPY_FORBIDDEN_KEYS = Set.of(
			"script", "indexed_shape", "runtime_mappings", "script_fields", "_search_template");

	/**
	 * Keys allowed on a per-field sort options object. {@code nested} is intentionally absent
	 * (Synapse search indexes have no nested documents, and a nested sort carries a filter query
	 * slot we do not want to surface).
	 */
	private static final Set<String> FIELD_SORT_KEYS = Set.of("order", "mode", "missing",
			"numeric_type", "unmapped_type", "format");

	private static final Set<String> SOURCE_FILTER_KEYS = Set.of("includes", "excludes");

	// ---------- top-level body ----------

	/**
	 * Copy the caller's {@code SearchQuery.body} into a new node containing only the top-level
	 * keys in {@link #BODY_ALLOWED_KEYS}. The sub-key values are deep-copied here and re-sanitized
	 * in full when each surface is parsed. The top-level key allowlist and the
	 * {@code search_after}/{@code from} conflict are checked upstream by
	 * {@link SearchDslValidator#scanBodyTopLevelKeys}.
	 */
	static ObjectNode sanitizeBodyTopLevel(JsonNode body) {
		return copyAllowlistedKeys(body, BODY_ALLOWED_KEYS, "body", "");
	}

	/** Autocomplete variant of {@link #sanitizeBodyTopLevel}. */
	static ObjectNode sanitizeAutocompleteBodyTopLevel(JsonNode body) {
		return copyAllowlistedKeys(body, AUTOCOMPLETE_BODY_ALLOWED_KEYS, "autocomplete body", "");
	}

	// ---------- _source ----------

	/** Rebuild a {@code _source} filter (boolean, string, array, or {@code {includes,excludes}}). */
	static JsonNode sanitizeSource(JsonNode node) {
		if (node == null) {
			throw new IllegalArgumentException("_source must not be null");
		}
		if (node.isBoolean() || node.isTextual() || node.isArray()) {
			return node.deepCopy();
		}
		if (node.isObject()) {
			return copyAllowlistedKeys(node, SOURCE_FILTER_KEYS, "_source", "_source");
		}
		throw new IllegalArgumentException(
				"_source must be a boolean, a field name, an array of field names, "
						+ "or an {includes, excludes} object");
	}

	// ---------- sort ----------

	/**
	 * Rebuild a {@code sort} clause (a string, an object, or an array of either). Field-name keys
	 * pass through; {@code _score} is allowed; any other underscore-prefixed key (notably
	 * {@code _script} and {@code _geo_distance}) is rejected — they are not supported and
	 * {@code _script} would run Painless.
	 */
	static JsonNode sanitizeSort(JsonNode node) {
		if (node == null) {
			throw new IllegalArgumentException("sort must not be null");
		}
		if (node.isTextual()) {
			return node.deepCopy();
		}
		if (node.isArray()) {
			ArrayNode out = NODES.arrayNode();
			for (int i = 0; i < node.size(); i++) {
				out.add(copySortElement(node.get(i), "sort[" + i + "]"));
			}
			return out;
		}
		return copySortElement(node, "sort");
	}

	static JsonNode copySortElement(JsonNode node, String path) {
		if (node.isTextual()) {
			return node.deepCopy();
		}
		if (node.isObject()) {
			return copySortObject(node, path);
		}
		throw new IllegalArgumentException(
				"sort entry at " + path + " must be a field name or a sort options object");
	}

	static ObjectNode copySortObject(JsonNode node, String path) {
		ObjectNode clean = NODES.objectNode();
		Iterator<Map.Entry<String, JsonNode>> it = node.fields();
		while (it.hasNext()) {
			Map.Entry<String, JsonNode> e = it.next();
			String key = e.getKey();
			JsonNode value = e.getValue();
			if ("_score".equals(key)) {
				clean.set(key, value.deepCopy());
			} else if (key.startsWith("_")) {
				throw new IllegalArgumentException("unsupported sort key '" + key + "' at " + path
						+ " (only a field name or '_score' is allowed; script and geo sorts are not)");
			} else if (value.isObject()) {
				clean.set(key, copyAllowlistedKeys(value, FIELD_SORT_KEYS, "sort options",
						path + "." + key));
			} else {
				clean.set(key, value.deepCopy());
			}
		}
		return clean;
	}

	// ---------- shared helpers ----------

	static ObjectNode copyAllowlistedKeys(JsonNode body, Set<String> allowedKeys,
			String surface, String path) {
		requireObject(body, path);
		ObjectNode clean = NODES.objectNode();
		Iterator<Map.Entry<String, JsonNode>> it = body.fields();
		while (it.hasNext()) {
			Map.Entry<String, JsonNode> e = it.next();
			if (!allowedKeys.contains(e.getKey())) {
				throw unsupported(surface, e.getKey(), path);
			}
			clean.set(e.getKey(), copyOpaque(e.getValue(), path + "." + e.getKey()));
		}
		return clean;
	}

	/**
	 * Scan a search-DSL surface (or any opaque sub-value within one) for a {@link #COPY_FORBIDDEN_KEYS}
	 * entry, throwing on the first one found anywhere in the tree. The typed POJO constrains the DSL
	 * <i>structure</i>, but several leaves remain opaque pass-through objects (a {@code highlight.options}
	 * map, a {@code term.value}, an aggregation {@code order}, ...) where a {@code script} or other
	 * forbidden construct could still hide; none of the forbidden keys is a legitimate key anywhere in
	 * the allowed DSL, so scanning the whole surface is both simple and safe. Bounded by
	 * {@link #FORBIDDEN_SCAN_MAX_DEPTH} so a pathologically nested opaque value can't blow the stack.
	 */
	static void scanForbiddenKeys(JsonNode value, String path) {
		scanForbiddenKeys(value, path, 0);
	}

	static void scanForbiddenKeys(JsonNode value, String path, int depth) {
		if (depth > FORBIDDEN_SCAN_MAX_DEPTH) {
			throw new IllegalArgumentException(
					"value at " + path + " is nested too deeply (max " + FORBIDDEN_SCAN_MAX_DEPTH + ")");
		}
		if (value.isObject()) {
			Iterator<Map.Entry<String, JsonNode>> it = value.fields();
			while (it.hasNext()) {
				Map.Entry<String, JsonNode> e = it.next();
				if (COPY_FORBIDDEN_KEYS.contains(e.getKey())) {
					throw new IllegalArgumentException(
							"forbidden key '" + e.getKey() + "' at " + path + "." + e.getKey());
				}
				scanForbiddenKeys(e.getValue(), path + "." + e.getKey(), depth + 1);
			}
		} else if (value.isArray()) {
			for (int i = 0; i < value.size(); i++) {
				scanForbiddenKeys(value.get(i), path + "[" + i + "]", depth + 1);
			}
		}
	}

	/**
	 * Deep-copy an opaque value (a scalar, an options object such as a {@code highlight.options}
	 * map, a value array, ...) while rejecting any {@link #COPY_FORBIDDEN_KEYS} entry encountered
	 * anywhere inside it. Used by the opaque {@code sort} / {@code _source} surfaces, which are
	 * structurally rebuilt from an allowlist (they have no typed POJO). Bounded by
	 * {@link #FORBIDDEN_SCAN_MAX_DEPTH}.
	 */
	static JsonNode copyOpaque(JsonNode value, String path) {
		return copyOpaque(value, path, 0);
	}

	static JsonNode copyOpaque(JsonNode value, String path, int depth) {
		if (depth > FORBIDDEN_SCAN_MAX_DEPTH) {
			throw new IllegalArgumentException(
					"value at " + path + " is nested too deeply (max " + FORBIDDEN_SCAN_MAX_DEPTH + ")");
		}
		if (value.isObject()) {
			ObjectNode out = NODES.objectNode();
			Iterator<Map.Entry<String, JsonNode>> it = value.fields();
			while (it.hasNext()) {
				Map.Entry<String, JsonNode> e = it.next();
				if (COPY_FORBIDDEN_KEYS.contains(e.getKey())) {
					throw new IllegalArgumentException(
							"forbidden key '" + e.getKey() + "' at " + path + "." + e.getKey());
				}
				out.set(e.getKey(), copyOpaque(e.getValue(), path + "." + e.getKey(), depth + 1));
			}
			return out;
		}
		if (value.isArray()) {
			ArrayNode out = NODES.arrayNode();
			for (int i = 0; i < value.size(); i++) {
				out.add(copyOpaque(value.get(i), path + "[" + i + "]", depth + 1));
			}
			return out;
		}
		return value.deepCopy();
	}

	static void requireObject(JsonNode node, String path) {
		if (node == null || !node.isObject()) {
			throw new IllegalArgumentException("expected a JSON object at " + path(path));
		}
	}

	private static IllegalArgumentException unsupported(String surface, String key, String path) {
		return new IllegalArgumentException("unsupported " + surface + " property '" + key + "'"
				+ (path == null || path.isEmpty() ? "" : " at " + path));
	}

	private static String path(String path) {
		return (path == null || path.isEmpty()) ? "the request body" : path;
	}

}
