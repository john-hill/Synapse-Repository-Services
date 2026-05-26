package org.sagebionetworks.repo.manager.search;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * In-place column-name → column-id rewriter for caller-supplied opaque OpenSearch DSL
 * payloads. The structured query surface used to do this with typed traversals; with the
 * DSL-pass-through query API, the same translation has to happen on the {@link JsonNode}
 * tree before the body is deserialized into OpenSearch's typed model.
 *
 * <p>Three kinds of payloads are rewritten by separate entry points so each can target the
 * field-bearing keys for its own DSL. The traversal is allowlist-driven (it only ever
 * descends into clauses the corresponding {@code *DslAllowlist} would have admitted) so
 * unfamiliar keys are left alone rather than silently rewritten.</p>
 *
 * <ul>
 *   <li>{@link #rewriteQuery(JsonNode, java.util.function.Function)} — the contents of the
 *       OpenSearch <code>query</code> key (a single clause object).</li>
 *   <li>{@link #rewriteAggregations(JsonNode, java.util.function.Function)} — a map of
 *       aggregation name to definition.</li>
 *   <li>{@link #rewriteSuggest(JsonNode, java.util.function.Function)} — a map of suggest
 *       name to suggester definition (plus an optional top-level <code>text</code>).</li>
 * </ul>
 *
 * <p>Each entry point also has an inverse — {@link #rewriteAggregationResults(JsonNode, java.util.function.Function)}
 * and {@link #rewriteSuggestResults(JsonNode, java.util.function.Function)} — for rewriting
 * column ids back to column names on the response side. The inverse traversals walk the
 * shape AOSS produces (aggregation results carry the agg name keyed by the caller, but the
 * field references need rewriting where they appear).</p>
 */
final class SearchFieldRewriter {

	private SearchFieldRewriter() {
	}

	/**
	 * Field keys that nest a query clause inside another clause. The values are arrays
	 * of clauses, an object mapping name to clause, or a single clause object — all walked
	 * recursively.
	 */
	private static final Set<String> NESTED_QUERY_KEYS = setOf(
			"must", "should", "must_not", "filter",   // bool
			"queries",                                  // dis_max
			"positive", "negative",                     // boosting
			"query"                                     // constant_score
	);

	/**
	 * Leaf clauses where the single inner key is the field name. The shape is
	 * {@code {"<clause>": {"<field>": ...}}} so the rewriter walks the clause's child
	 * object keys and rewrites each one.
	 */
	private static final Set<String> FIELD_KEYED_CLAUSES = setOf(
			"match", "match_phrase", "match_phrase_prefix", "match_bool_prefix",
			"term", "terms", "range", "prefix", "wildcard", "fuzzy"
	);

	/**
	 * Clauses where the field reference is a {@code "field"} string property (or a
	 * {@code "fields"} array of strings).
	 */
	private static final Set<String> FIELD_STRING_CLAUSES = setOf("exists");
	private static final Set<String> FIELDS_ARRAY_CLAUSES = setOf("multi_match", "simple_query_string");

	private static Set<String> setOf(String... values) {
		Set<String> set = new LinkedHashSet<>();
		for (String v : values) {
			set.add(v);
		}
		return set;
	}

	// ---------- request-side rewrites ----------

	/**
	 * Rewrite every column-name field reference inside a {@code query} subtree to its
	 * column id, using the supplied resolver. Unknown names are passed through unchanged
	 * (the existing structured-surface posture).
	 */
	static void rewriteQuery(JsonNode node, java.util.function.Function<String, String> nameToId) {
		if (node == null || !node.isObject() || node.size() != 1) {
			return;
		}
		String clause = node.fieldNames().next();
		JsonNode body = node.get(clause);
		if (body == null) {
			return;
		}
		if (NESTED_QUERY_KEYS.contains(clause)) {
			// Shouldn't happen at the top of a query subtree, but defensive
			recurseQueryChildren(body, nameToId);
			return;
		}
		if ("bool".equals(clause) || "dis_max".equals(clause) || "boosting".equals(clause)
				|| "constant_score".equals(clause)) {
			recurseCompound(body, nameToId);
			return;
		}
		if (FIELD_KEYED_CLAUSES.contains(clause) && body.isObject()) {
			renameObjectKeys((ObjectNode) body, nameToId);
			return;
		}
		if (FIELD_STRING_CLAUSES.contains(clause) && body.isObject()) {
			renameFieldString((ObjectNode) body, "field", nameToId);
			return;
		}
		if (FIELDS_ARRAY_CLAUSES.contains(clause) && body.isObject()) {
			renameFieldsArray((ObjectNode) body, "fields", nameToId);
			return;
		}
		// match_all, ids, and any clause with no field reference: nothing to rewrite.
	}

	private static void recurseCompound(JsonNode body, java.util.function.Function<String, String> nameToId) {
		if (body == null || !body.isObject()) {
			return;
		}
		Iterator<Map.Entry<String, JsonNode>> fields = body.fields();
		while (fields.hasNext()) {
			Map.Entry<String, JsonNode> e = fields.next();
			if (NESTED_QUERY_KEYS.contains(e.getKey())) {
				recurseQueryChildren(e.getValue(), nameToId);
			}
		}
	}

	private static void recurseQueryChildren(JsonNode value, java.util.function.Function<String, String> nameToId) {
		if (value == null) {
			return;
		}
		if (value.isArray()) {
			for (JsonNode element : value) {
				rewriteQuery(element, nameToId);
			}
		} else if (value.isObject()) {
			rewriteQuery(value, nameToId);
		}
	}

	/**
	 * Rewrite every column-name field reference inside an aggregations object. Walks each
	 * aggregation's {@code field} string and recurses into nested {@code aggs} /
	 * {@code aggregations}.
	 */
	static void rewriteAggregations(JsonNode node, java.util.function.Function<String, String> nameToId) {
		if (node == null || !node.isObject()) {
			return;
		}
		Iterator<Map.Entry<String, JsonNode>> aggs = node.fields();
		while (aggs.hasNext()) {
			rewriteAggregationDef(aggs.next().getValue(), nameToId);
		}
	}

	private static void rewriteAggregationDef(JsonNode def, java.util.function.Function<String, String> nameToId) {
		if (def == null || !def.isObject()) {
			return;
		}
		Iterator<Map.Entry<String, JsonNode>> fields = def.fields();
		while (fields.hasNext()) {
			Map.Entry<String, JsonNode> entry = fields.next();
			String key = entry.getKey();
			JsonNode value = entry.getValue();
			if ("aggs".equals(key) || "aggregations".equals(key)) {
				rewriteAggregations(value, nameToId);
			} else if (value != null && value.isObject()) {
				// Inside any aggregation type body, rewrite a `field` string if present.
				renameFieldString((ObjectNode) value, "field", nameToId);
			}
		}
	}

	/**
	 * Rewrite every column-name field reference inside a suggesters object (the contents
	 * of the {@code suggest} key). Each suggester definition's inner term/phrase/completion
	 * body has a {@code field} string.
	 */
	static void rewriteSuggest(JsonNode node, java.util.function.Function<String, String> nameToId) {
		if (node == null || !node.isObject()) {
			return;
		}
		Iterator<Map.Entry<String, JsonNode>> entries = node.fields();
		while (entries.hasNext()) {
			Map.Entry<String, JsonNode> entry = entries.next();
			if ("text".equals(entry.getKey())) {
				continue;
			}
			JsonNode def = entry.getValue();
			if (def == null || !def.isObject()) {
				continue;
			}
			Iterator<Map.Entry<String, JsonNode>> defFields = def.fields();
			while (defFields.hasNext()) {
				Map.Entry<String, JsonNode> field = defFields.next();
				JsonNode body = field.getValue();
				if (body != null && body.isObject()) {
					renameFieldString((ObjectNode) body, "field", nameToId);
				}
			}
		}
	}

	// ---------- response-side rewrites ----------

	/**
	 * Inverse of {@link #rewriteAggregations}: walks the AOSS response's aggregation block
	 * and rewrites any embedded column-id field reference back to its column name. The
	 * caller's aggregation-name keys are unchanged (those are caller-chosen labels, not
	 * field references).
	 */
	static void rewriteAggregationResults(JsonNode node, java.util.function.Function<String, String> idToName) {
		if (node == null) {
			return;
		}
		if (node.isObject()) {
			ObjectNode obj = (ObjectNode) node;
			Iterator<Map.Entry<String, JsonNode>> fields = obj.fields();
			while (fields.hasNext()) {
				Map.Entry<String, JsonNode> entry = fields.next();
				if ("field".equals(entry.getKey()) && entry.getValue().isTextual()) {
					String mapped = idToName.apply(entry.getValue().asText());
					if (mapped != null) {
						obj.set("field", new TextNode(mapped));
					}
				} else {
					rewriteAggregationResults(entry.getValue(), idToName);
				}
			}
		} else if (node.isArray()) {
			for (JsonNode element : node) {
				rewriteAggregationResults(element, idToName);
			}
		}
	}

	/**
	 * Inverse of {@link #rewriteSuggest}: same shape as {@link #rewriteAggregationResults}
	 * — any embedded {@code "field"} string in the suggest response gets remapped back to
	 * the caller's column name.
	 */
	static void rewriteSuggestResults(JsonNode node, java.util.function.Function<String, String> idToName) {
		// Same generic walk works for the suggest response; the only field-bearing key is
		// the same `field` string.
		rewriteAggregationResults(node, idToName);
	}

	// ---------- low-level field helpers ----------

	/**
	 * Rename every immediate-child key of {@code obj} that maps via {@code nameToId},
	 * preserving the value. Does nothing if a child key is not in the map.
	 */
	private static void renameObjectKeys(ObjectNode obj,
			java.util.function.Function<String, String> nameToId) {
		ObjectNode rebuilt = obj.objectNode();
		Iterator<Map.Entry<String, JsonNode>> fields = obj.fields();
		boolean changed = false;
		while (fields.hasNext()) {
			Map.Entry<String, JsonNode> entry = fields.next();
			String mapped = nameToId.apply(entry.getKey());
			String key = (mapped != null && !mapped.equals(entry.getKey())) ? mapped : entry.getKey();
			if (mapped != null && !mapped.equals(entry.getKey())) {
				changed = true;
			}
			rebuilt.set(key, entry.getValue());
		}
		if (changed) {
			obj.removeAll();
			obj.setAll(rebuilt);
		}
	}

	private static void renameFieldString(ObjectNode obj, String fieldKey,
			java.util.function.Function<String, String> nameToId) {
		JsonNode existing = obj.get(fieldKey);
		if (existing != null && existing.isTextual()) {
			String mapped = nameToId.apply(existing.asText());
			if (mapped != null) {
				obj.set(fieldKey, new TextNode(mapped));
			}
		}
	}

	private static void renameFieldsArray(ObjectNode obj, String fieldsKey,
			java.util.function.Function<String, String> nameToId) {
		JsonNode existing = obj.get(fieldsKey);
		if (existing == null || !existing.isArray()) {
			return;
		}
		ArrayNode rebuilt = obj.arrayNode();
		boolean changed = false;
		for (JsonNode element : existing) {
			if (element.isTextual()) {
				String raw = element.asText();
				// Preserve "field^boost" suffix if present.
				int caret = raw.indexOf('^');
				String namePart = caret >= 0 ? raw.substring(0, caret) : raw;
				String boostPart = caret >= 0 ? raw.substring(caret) : "";
				String mapped = nameToId.apply(namePart);
				if (mapped != null && !mapped.equals(namePart)) {
					rebuilt.add(new TextNode(mapped + boostPart));
					changed = true;
				} else {
					rebuilt.add(element);
				}
			} else {
				rebuilt.add(element);
			}
		}
		if (changed) {
			obj.set(fieldsKey, rebuilt);
		}
	}
}
