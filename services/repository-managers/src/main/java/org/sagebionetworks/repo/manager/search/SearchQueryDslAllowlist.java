package org.sagebionetworks.repo.manager.search;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Allowlist validator for a caller-supplied OpenSearch query-DSL subtree (the contents of
 * the {@code query} key of a search request, never the whole request).
 *
 * <p><b>Why an allowlist, not a denylist.</b> A denylist must chase every new dangerous or
 * cross-index construct OpenSearch ships; the gap between a new release and an updated
 * denylist is an exposure window. An allowlist is safe-by-default: only the enumerated
 * clause types are permitted, so a newly-introduced clause is rejected until it is
 * deliberately added here after review. The cost is a small, controlled lag whenever a new
 * leaf query type is wanted — an explicit, reviewable change rather than a silent risk.</p>
 *
 * <p><b>What this protects against.</b></p>
 * <ul>
 *   <li><b>Script injection</b> — {@code script} / {@code script_score} (Painless execution)
 *       are not in the allowlist, and a defensive key scan rejects an embedded {@code script}
 *       key anywhere in an otherwise-allowed clause.</li>
 *   <li><b>Cross-collection reach</b> — the SearchIndex query must stay within the single
 *       index the server resolved. Clause types that can reference another index
 *       ({@code more_like_this}, {@code geo_shape}/{@code shape} with an indexed shape,
 *       {@code has_child}/{@code has_parent}, {@code terms} lookup, {@code percolate}) are
 *       not allowlisted; the {@code terms} lookup form is additionally rejected explicitly
 *       since {@code terms} itself is allowed in its inline form.</li>
 *   <li><b>Validation bypass</b> — {@code wrapper} (a base64-encoded query that would evade
 *       this walk entirely) is not allowlisted.</li>
 *   <li><b>Resource exhaustion</b> — depth and total-clause caps bound query cost.</li>
 * </ul>
 *
 * <p>Row-level access control is NOT this validator's concern: a SearchIndex only ever
 * indexes public data (the build runs as the anonymous user and only DataType.OPEN_DATA +
 * PUBLIC tables can be indexed), and the manager AND-s its own filter context around this
 * subtree regardless. This validator's job is strictly to keep the query benign and
 * single-collection.</p>
 *
 * <p>Throws {@link IllegalArgumentException} (HTTP 400) on any violation.</p>
 */
final class SearchQueryDslAllowlist {

	private SearchQueryDslAllowlist() {
	}

	/**
	 * Clause types a caller may use. Compound clauses recurse into their nested query slots;
	 * everything not listed here is rejected. Intentionally excludes script-bearing
	 * ({@code script}, {@code script_score}, {@code function_score}), cross-index
	 * ({@code more_like_this}, {@code geo_shape}, {@code has_child}, {@code has_parent},
	 * {@code percolate}), and validation-bypassing ({@code wrapper}) clauses.
	 */
	static final Set<String> ALLOWED_CLAUSES = Set.of(
			// leaf
			"match", "multi_match", "match_phrase", "match_phrase_prefix", "match_bool_prefix",
			"term", "terms", "range", "exists", "prefix", "wildcard", "fuzzy", "ids",
			"simple_query_string", "match_all",
			// compound
			"bool", "dis_max", "constant_score", "boosting");

	/** Keys that must never appear anywhere in the subtree, even inside an allowed clause. */
	private static final Set<String> FORBIDDEN_KEYS = Set.of("script", "indexed_shape");

	static final int MAX_DEPTH = 20;
	static final int MAX_CLAUSES = 256;

	/**
	 * Validate a query-DSL subtree. {@code root} is the contents of the {@code query} key —
	 * e.g. {@code { "match": { "abstract": "amyloid" } }} or a {@code bool} / {@code dis_max}
	 * tree.
	 *
	 * @throws IllegalArgumentException if the subtree contains a non-allowlisted clause, a
	 *         forbidden key, a {@code terms} lookup, or exceeds the depth / clause caps.
	 */
	static void validate(JsonNode root) {
		if (root == null || root.isNull()) {
			throw new IllegalArgumentException("query must not be null");
		}
		validateClause(root, 1, new int[] { 0 });
	}

	private static void validateClause(JsonNode node, int depth, int[] clauseCount) {
		if (depth > MAX_DEPTH) {
			throw new IllegalArgumentException(
					"query is nested too deeply (max depth " + MAX_DEPTH + ")");
		}
		if (node == null || !node.isObject()) {
			throw new IllegalArgumentException("each query clause must be a JSON object");
		}
		if (node.size() != 1) {
			throw new IllegalArgumentException(
					"each query clause must have exactly one clause type; found " + node.size()
							+ " keys: " + fieldNames(node));
		}
		String clause = node.fieldNames().next();
		if (!ALLOWED_CLAUSES.contains(clause)) {
			throw new IllegalArgumentException("query clause type is not allowed: '" + clause
					+ "'. Allowed types: " + ALLOWED_CLAUSES);
		}
		if (++clauseCount[0] > MAX_CLAUSES) {
			throw new IllegalArgumentException(
					"query has too many clauses (max " + MAX_CLAUSES + ")");
		}
		JsonNode body = node.get(clause);
		switch (clause) {
			case "bool":
				for (String slot : Arrays.asList("must", "should", "must_not", "filter")) {
					JsonNode sub = body.get(slot);
					if (sub != null) {
						validateQueryOrArray(sub, depth + 1, clauseCount);
					}
				}
				break;
			case "dis_max":
				validateQueryOrArray(requireField(body, "queries", "dis_max"), depth + 1, clauseCount);
				break;
			case "constant_score":
				validateClause(requireField(body, "filter", "constant_score"), depth + 1, clauseCount);
				break;
			case "boosting":
				validateClause(requireField(body, "positive", "boosting"), depth + 1, clauseCount);
				validateClause(requireField(body, "negative", "boosting"), depth + 1, clauseCount);
				break;
			case "terms":
				rejectTermsLookup(body);
				scanForbiddenKeys(body);
				break;
			default:
				// Leaf clause. Defensively scan for forbidden keys (e.g. an embedded script)
				// so an allowed wrapper clause can't smuggle one in.
				scanForbiddenKeys(body);
		}
	}

	/** A bool/dis_max query slot may be a single clause object or an array of them. */
	private static void validateQueryOrArray(JsonNode node, int depth, int[] clauseCount) {
		if (node.isArray()) {
			for (JsonNode element : node) {
				validateClause(element, depth, clauseCount);
			}
		} else {
			validateClause(node, depth, clauseCount);
		}
	}

	/**
	 * A {@code terms} body is {@code {field: [values], ...optional boost/_name}}. The
	 * cross-index lookup form is {@code {field: {index, id, path}}}. Reject any field whose
	 * value is an object — only inline array (or scalar) values are permitted.
	 */
	private static void rejectTermsLookup(JsonNode termsBody) {
		if (!termsBody.isObject()) {
			throw new IllegalArgumentException("terms clause must be an object");
		}
		Iterator<java.util.Map.Entry<String, JsonNode>> fields = termsBody.fields();
		while (fields.hasNext()) {
			java.util.Map.Entry<String, JsonNode> field = fields.next();
			if (field.getValue().isObject()) {
				throw new IllegalArgumentException(
						"terms lookup form is not allowed (cross-index reference): '"
								+ field.getKey() + "'");
			}
		}
	}

	private static void scanForbiddenKeys(JsonNode node) {
		if (node == null) {
			return;
		}
		if (node.isObject()) {
			Iterator<java.util.Map.Entry<String, JsonNode>> fields = node.fields();
			while (fields.hasNext()) {
				java.util.Map.Entry<String, JsonNode> field = fields.next();
				if (FORBIDDEN_KEYS.contains(field.getKey())) {
					throw new IllegalArgumentException(
							"forbidden key in query: '" + field.getKey() + "'");
				}
				scanForbiddenKeys(field.getValue());
			}
		} else if (node.isArray()) {
			for (JsonNode element : node) {
				scanForbiddenKeys(element);
			}
		}
	}

	private static JsonNode requireField(JsonNode body, String field, String clause) {
		JsonNode value = body == null ? null : body.get(field);
		if (value == null) {
			throw new IllegalArgumentException(
					"'" + clause + "' clause requires a '" + field + "' field");
		}
		return value;
	}

	private static List<String> fieldNames(JsonNode node) {
		java.util.ArrayList<String> names = new java.util.ArrayList<>();
		node.fieldNames().forEachRemaining(names::add);
		return names;
	}
}
