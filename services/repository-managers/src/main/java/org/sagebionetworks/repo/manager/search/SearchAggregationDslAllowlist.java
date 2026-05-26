package org.sagebionetworks.repo.manager.search;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Allowlist validator for a caller-supplied OpenSearch aggregations object (the contents of
 * the {@code aggs} / {@code aggregations} key of a search request — a map of aggregation name
 * to aggregation definition).
 *
 * <p>Same posture as {@link SearchQueryDslAllowlist}: only the enumerated aggregation types
 * are permitted, so anything new — and anything dangerous — is rejected until deliberately
 * added here after review. This keeps exploration aggregations expressive (histograms, stats,
 * cardinality, ranges, nested sub-aggregations) while excluding the cost/safety hazards:
 * script-based aggregations ({@code scripted_metric} and friends) and pipeline aggregations
 * are simply not allowlisted, and a defensive key scan rejects an embedded {@code script}
 * anywhere in an aggregation body. Depth and total-count caps bound query cost.</p>
 *
 * <p>Throws {@link IllegalArgumentException} (HTTP 400) on any violation.</p>
 */
final class SearchAggregationDslAllowlist {

	private SearchAggregationDslAllowlist() {
	}

	/**
	 * Aggregation types a caller may use. Bucket aggregations may carry nested sub-aggregations
	 * via {@code aggs}/{@code aggregations}. Intentionally excludes {@code scripted_metric},
	 * pipeline aggregations (e.g. {@code bucket_script}, {@code bucket_selector}), and the
	 * {@code filter}/{@code filters} aggregations (whose bodies are queries that would need
	 * separate query-DSL validation).
	 */
	static final Set<String> ALLOWED_AGGREGATIONS = Set.of(
			// bucket
			"terms", "histogram", "date_histogram", "range", "date_range", "missing",
			// metric
			"min", "max", "avg", "sum", "stats", "extended_stats", "value_count", "cardinality");

	/** Structural keys on an aggregation definition that are not themselves an aggregation type. */
	private static final Set<String> NESTED_KEYS = Set.of("aggs", "aggregations");
	private static final String META_KEY = "meta";

	/** Keys that must never appear anywhere in an aggregation definition. */
	private static final Set<String> FORBIDDEN_KEYS = Set.of("script", "indexed_shape");

	static final int MAX_DEPTH = 10;
	static final int MAX_AGGREGATIONS = 100;

	/**
	 * Validate an aggregations object — the top-level map of aggregation name to definition.
	 *
	 * @throws IllegalArgumentException if a definition uses a non-allowlisted aggregation type,
	 *         carries a forbidden key, or the tree exceeds the depth / count caps.
	 */
	static void validate(JsonNode aggregations) {
		if (aggregations == null || aggregations.isNull()) {
			throw new IllegalArgumentException("aggregations must not be null");
		}
		if (!aggregations.isObject()) {
			throw new IllegalArgumentException(
					"aggregations must be a JSON object mapping aggregation name to definition");
		}
		validateAggregationMap(aggregations, 1, new int[] { 0 });
	}

	private static void validateAggregationMap(JsonNode map, int depth, int[] count) {
		if (depth > MAX_DEPTH) {
			throw new IllegalArgumentException(
					"aggregations are nested too deeply (max depth " + MAX_DEPTH + ")");
		}
		Iterator<Map.Entry<String, JsonNode>> entries = map.fields();
		while (entries.hasNext()) {
			validateAggregationDef(entries.next().getValue(), depth, count);
		}
	}

	private static void validateAggregationDef(JsonNode def, int depth, int[] count) {
		if (def == null || !def.isObject()) {
			throw new IllegalArgumentException("each aggregation definition must be a JSON object");
		}
		if (++count[0] > MAX_AGGREGATIONS) {
			throw new IllegalArgumentException(
					"too many aggregations (max " + MAX_AGGREGATIONS + ")");
		}
		String aggType = null;
		Iterator<Map.Entry<String, JsonNode>> fields = def.fields();
		while (fields.hasNext()) {
			Map.Entry<String, JsonNode> field = fields.next();
			String key = field.getKey();
			if (NESTED_KEYS.contains(key)) {
				validateAggregationMap(field.getValue(), depth + 1, count);
			} else if (META_KEY.equals(key)) {
				// arbitrary caller metadata — left as-is
			} else if (ALLOWED_AGGREGATIONS.contains(key)) {
				if (aggType != null) {
					throw new IllegalArgumentException(
							"an aggregation definition must declare exactly one aggregation type; found '"
									+ aggType + "' and '" + key + "'");
				}
				aggType = key;
				scanForbiddenKeys(field.getValue());
			} else {
				throw new IllegalArgumentException("aggregation type is not allowed: '" + key
						+ "'. Allowed types: " + ALLOWED_AGGREGATIONS);
			}
		}
		if (aggType == null) {
			throw new IllegalArgumentException(
					"each aggregation definition must declare exactly one aggregation type");
		}
	}

	private static void scanForbiddenKeys(JsonNode node) {
		if (node == null) {
			return;
		}
		if (node.isObject()) {
			Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
			while (fields.hasNext()) {
				Map.Entry<String, JsonNode> field = fields.next();
				if (FORBIDDEN_KEYS.contains(field.getKey())) {
					throw new IllegalArgumentException(
							"forbidden key in aggregation: '" + field.getKey() + "'");
				}
				scanForbiddenKeys(field.getValue());
			}
		} else if (node.isArray()) {
			for (JsonNode element : node) {
				scanForbiddenKeys(element);
			}
		}
	}
}
