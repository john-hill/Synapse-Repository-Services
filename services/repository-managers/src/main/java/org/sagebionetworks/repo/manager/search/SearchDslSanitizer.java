package org.sagebionetworks.repo.manager.search;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Phase 1 of the two-phase search-DSL gate: the allowlist-by-construction sanitizer. Each
 * {@code sanitize*} method walks the caller's raw {@link JsonNode} depth-first and builds a
 * brand-new node that contains only the keys and leaves Synapse explicitly supports. The first key
 * that is not recognized at its position throws {@link IllegalArgumentException} (HTTP 400) naming
 * the offending key and its JSON path &mdash; nothing is ever silently dropped. Only the clean node
 * is field-rewritten and handed to the typed OpenSearch deserializer; the typed resource caps then
 * run as defense in depth in {@link SearchDslValidator}.
 *
 * <p>The walk enforces two things in a single pass:</p>
 * <ul>
 *   <li><b>Structural allowlist.</b> The clause / aggregation kind and every property of its body
 *       is checked against a fixed set for that position; compound query clauses ({@code bool} /
 *       {@code dis_max} / {@code constant_score} / {@code boosting}) recurse into their
 *       nested-query slots, bucket aggregations recurse into their sub-aggregations. The set of
 *       permitted keys is context-dependent &mdash; a kind allowed at top level may be narrowed
 *       elsewhere (autocomplete top-level is narrower than a general query).</li>
 *   <li><b>Forbidden-key blacklist on opaque values.</b> A handful of permitted properties are
 *       opaque pass-through objects ({@code highlight.options}, an aggregation {@code meta} block,
 *       a leaf-query option value). A {@code script} or other {@link #COPY_FORBIDDEN_KEYS} entry
 *       could hide inside one, so the copy of every opaque value rejects those keys as it
 *       descends.</li>
 * </ul>
 *
 * <p><b>Why construction, not a denylist.</b> A denylist must chase every new dangerous or
 * cross-index construct OpenSearch ships. Building the request from an allowlist is
 * safe-by-default: only the enumerated kinds and keys survive, so a newly-introduced kind or
 * property is rejected until it is deliberately added here.</p>
 *
 * <p><b>What this protects against.</b></p>
 * <ul>
 *   <li><b>Script injection</b> &mdash; the script-bearing query kinds ({@code script},
 *       {@code script_score}, {@code function_score}) are not allowlisted, and a {@code script}
 *       property on an otherwise-allowed variant (e.g. {@code terms} / {@code date_histogram}
 *       aggregation) never makes it into the rebuilt node.</li>
 *   <li><b>Cross-collection reach</b> &mdash; clause kinds that can reference another index
 *       ({@code more_like_this}, {@code geo_shape}, {@code has_child}, {@code has_parent},
 *       {@code percolate}) are not allowlisted; the {@code terms} lookup form is additionally
 *       rejected since the inline {@code terms} form is allowed.</li>
 *   <li><b>Validation bypass</b> &mdash; {@code wrapper} (a base64-encoded query that would evade
 *       the walk entirely) is not allowlisted.</li>
 * </ul>
 *
 * <p>The numeric resource caps (depth, clause count, value-array length, prefix expansion,
 * histogram bucket bound, cardinality precision, leading-wildcard rejection) run afterwards on the
 * typed objects in {@link SearchDslValidator}.</p>
 *
 * <p>Throws {@link IllegalArgumentException} (HTTP 400) on any violation.</p>
 */
final class SearchDslSanitizer {

	/**
	 * Maximum recursion depth when copying an opaque pass-through value (see {@link #copyOpaque}).
	 * Independent of the per-surface clause/agg depth caps, which only bound DSL structure &mdash;
	 * an opaque options/meta value can still be arbitrarily nested JSON, and an unbounded recursive
	 * copy would blow the stack on a pathological input.
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

	private SearchDslSanitizer() {
	}

	// --------------------------------------------------------------
	// Top-level body validation.
	// --------------------------------------------------------------

	/**
	 * Reject any top-level key on {@code SearchQuery.body} outside {@link #BODY_ALLOWED_KEYS}.
	 * Also rejects {@code search_after} alongside {@code from > 0} (mutually exclusive).
	 */
	static void scanBodyTopLevelKeys(JsonNode body) {
		if (body == null || !body.isObject()) {
			throw new IllegalArgumentException("body must be a JSON object");
		}
		Iterator<Map.Entry<String, JsonNode>> fields = body.fields();
		while (fields.hasNext()) {
			String key = fields.next().getKey();
			if (!BODY_ALLOWED_KEYS.contains(key)) {
				throw new IllegalArgumentException(
						"unsupported top-level key in body: '" + key + "'");
			}
		}
		JsonNode searchAfter = body.get("search_after");
		JsonNode from = body.get("from");
		if (searchAfter != null && !searchAfter.isNull() && from != null
				&& from.isNumber() && from.asLong() > 0L) {
			throw new IllegalArgumentException(
					"body.search_after and body.from > 0 are mutually exclusive");
		}
	}

	/**
	 * Narrow body validator for autocomplete: only {@code query} and {@code _source} are
	 * allowed at the top level.
	 */
	static void scanAutocompleteBodyTopLevelKeys(JsonNode body) {
		if (body == null || !body.isObject()) {
			throw new IllegalArgumentException("body must be a JSON object");
		}
		Iterator<Map.Entry<String, JsonNode>> fields = body.fields();
		while (fields.hasNext()) {
			String key = fields.next().getKey();
			if (!AUTOCOMPLETE_BODY_ALLOWED_KEYS.contains(key)) {
				throw new IllegalArgumentException(
						"unsupported top-level key in autocomplete body: '" + key + "'");
			}
		}
	}

	/**
	 * Validate and resolve the effective {@code from} offset on a body. Defaults to {@code 0}
	 * when absent; rejects non-integral values and anything outside {@code 0..Integer.MAX_VALUE}.
	 */
	static int resolveFrom(JsonNode body) {
		JsonNode node = body.get("from");
		if (node == null || node.isNull()) {
			return 0;
		}
		if (!node.isIntegralNumber()) {
			throw new IllegalArgumentException("body.from must be an integer");
		}
		long value = node.asLong();
		if (value < 0L || value > Integer.MAX_VALUE) {
			throw new IllegalArgumentException(
					"body.from must be between 0 and " + Integer.MAX_VALUE);
		}
		return (int) value;
	}

	/**
	 * Validate and resolve the effective {@code size} on a body. Defaults to {@code defaultSize}
	 * when absent; rejects non-integral and negative values; clamps anything above
	 * {@code maxSize} down to {@code maxSize}.
	 */
	static int resolveSize(JsonNode body, int defaultSize, int maxSize) {
		JsonNode node = body.get("size");
		if (node == null || node.isNull()) {
			return defaultSize;
		}
		if (!node.isIntegralNumber()) {
			throw new IllegalArgumentException("body.size must be an integer");
		}
		long value = node.asLong();
		if (value < 0L) {
			throw new IllegalArgumentException("body.size must be non-negative");
		}
		return (int) Math.min(value, maxSize);
	}

	/**
	 * Validate the structural shape of {@code search_after}: when present and non-null it must be
	 * a JSON array. The {@code search_after} / {@code from > 0} exclusivity rule is enforced
	 * separately in {@link #scanBodyTopLevelKeys}.
	 */
	static void validateSearchAfterShape(JsonNode body) {
		JsonNode node = body.get("search_after");
		if (node != null && !node.isNull() && !node.isArray()) {
			throw new IllegalArgumentException("body.search_after must be an array");
		}
	}

	// ==============================================================
	// Copy-only sanitizer (allowlist by construction).
	//
	// Each sanitize* method rebuilds a caller-supplied surface as a brand-new JsonNode containing
	// only the keys and leaves Synapse supports, throwing on the first key it does not recognize
	// at that position. The clean node is the only thing field-rewritten and handed to the typed
	// OpenSearch deserializer.
	//
	// The allowlists below are the OpenSearch DSL property names for each clause / aggregation /
	// search-feature body, with every script-bearing property deliberately omitted —
	// so a script on an otherwise-allowed variant (e.g. a terms aggregation's script) never enters
	// the rebuilt node. The typed validate* caps run afterwards as defense in depth.
	// ==============================================================

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	/**
	 * Keys forbidden anywhere inside an opaque pass-through value the structural allowlist copies
	 * wholesale (a {@code highlight.options} map, an aggregation {@code meta} block, a leaf-query
	 * option value, ...). The structural allowlists
	 * already exclude these as direct body keys; this set is what the copy of every opaque value
	 * additionally rejects as it descends, so a {@code script} cannot ride in on an opaque slot:
	 * {@code script} (Painless on any variant), {@code indexed_shape} (cross-index geo reference),
	 * {@code runtime_mappings} / {@code script_fields} (Painless), and {@code _search_template}.
	 */
	private static final Set<String> COPY_FORBIDDEN_KEYS = Set.of(
			"script", "indexed_shape", "runtime_mappings", "script_fields", "_search_template");

	/** Every allowlisted query kind, by its OpenSearch JSON name. Used only for error text. */
	private static final Set<String> ALLOWED_QUERY_KIND_NAMES = Set.of(
			"match", "multi_match", "match_phrase", "match_phrase_prefix", "match_bool_prefix",
			"term", "terms", "range", "exists", "prefix", "wildcard", "fuzzy", "ids",
			"simple_query_string", "match_all", "bool", "dis_max", "constant_score", "boosting");

	/** Autocomplete top-level kinds, by JSON name — mirrors {@link SearchDslValidator#ALLOWED_AUTOCOMPLETE_TOP_LEVEL}. */
	private static final Set<String> ALLOWED_AUTOCOMPLETE_TOP_LEVEL_NAMES = Set.of(
			"prefix", "match_phrase_prefix", "match_bool_prefix");

	/**
	 * Leaf query kinds that accept the OpenSearch shorthand form where the body's single key is
	 * the field name (e.g. {@code {"match": {"title": "x"}}}). {@code terms} is field-keyed too
	 * but is handled separately ({@link #copyTerms}); {@code exists} / {@code ids} /
	 * {@code multi_match} / {@code simple_query_string} / {@code match_all} are long-form only.
	 */
	private static final Set<String> SHORTHAND_QUERY_KIND_NAMES = Set.of(
			"match", "match_phrase", "match_phrase_prefix", "match_bool_prefix",
			"term", "range", "prefix", "wildcard", "fuzzy");

	/** Sibling keys allowed alongside the field-keyed value array on a {@code terms} clause. */
	private static final Set<String> TERMS_SIBLING_KEYS = Set.of("_name", "boost", "value_type");

	/**
	 * Per-leaf-kind option keys (the long-form body properties, and the per-field options object
	 * in the shorthand form). {@code field} / {@code fields} are accepted separately in long form.
	 */
	private static final Map<String, Set<String>> QUERY_LEAF_OPTION_KEYS = Map.ofEntries(
			Map.entry("match", Set.of("_name", "analyzer", "auto_generate_synonyms_phrase_query",
					"boost", "cutoff_frequency", "fuzziness", "fuzzy_rewrite", "fuzzy_transpositions",
					"lenient", "max_expansions", "minimum_should_match", "operator", "prefix_length",
					"query", "zero_terms_query")),
			Map.entry("match_phrase", Set.of("_name", "analyzer", "boost", "query", "slop",
					"zero_terms_query")),
			Map.entry("match_phrase_prefix", Set.of("_name", "analyzer", "boost", "max_expansions",
					"query", "slop", "zero_terms_query")),
			Map.entry("match_bool_prefix", Set.of("_name", "analyzer", "boost", "fuzziness",
					"fuzzy_rewrite", "fuzzy_transpositions", "max_expansions", "minimum_should_match",
					"operator", "prefix_length", "query")),
			Map.entry("multi_match", Set.of("_name", "analyzer",
					"auto_generate_synonyms_phrase_query", "boost", "cutoff_frequency", "fuzziness",
					"fuzzy_rewrite", "fuzzy_transpositions", "lenient", "max_expansions",
					"minimum_should_match", "operator", "prefix_length", "query", "slop",
					"tie_breaker", "type", "zero_terms_query")),
			Map.entry("term", Set.of("_name", "boost", "case_insensitive", "value")),
			Map.entry("range", Set.of("_name", "boost", "format", "from", "gt", "gte",
					"include_lower", "include_upper", "lt", "lte", "relation", "time_zone", "to")),
			Map.entry("exists", Set.of("_name", "boost")),
			Map.entry("prefix", Set.of("_name", "boost", "case_insensitive", "rewrite", "value")),
			Map.entry("wildcard", Set.of("_name", "boost", "case_insensitive", "rewrite", "value",
					"wildcard")),
			Map.entry("fuzzy", Set.of("_name", "boost", "fuzziness", "max_expansions",
					"prefix_length", "rewrite", "transpositions", "value")),
			Map.entry("ids", Set.of("_name", "boost", "values")),
			Map.entry("simple_query_string", Set.of("_name", "analyze_wildcard", "analyzer",
					"auto_generate_synonyms_phrase_query", "boost", "default_operator", "flags",
					"fuzzy_max_expansions", "fuzzy_prefix_length", "fuzzy_transpositions", "lenient",
					"minimum_should_match", "query", "quote_field_suffix")),
			Map.entry("match_all", Set.of("_name", "boost")));

	/**
	 * Per-aggregation-kind body keys. {@code aggregations} (sub-aggregations) and
	 * {@code meta} are handled by the container walk, not here. {@code script} is intentionally
	 * absent from every entry — that is the whole point of the allowlist.
	 */
	static final Map<String, Set<String>> AGG_BODY_KEYS = Map.ofEntries(
			Map.entry("terms", Set.of("collect_mode", "exclude", "execution_hint", "field", "format",
					"include", "min_doc_count", "missing", "order", "shard_min_doc_count", "shard_size",
					"show_term_doc_count_error", "size", "value_type")),
			Map.entry("histogram", Set.of("extended_bounds", "field", "format", "hard_bounds",
					"interval", "keyed", "min_doc_count", "missing", "offset", "order")),
			Map.entry("date_histogram", Set.of("calendar_interval", "extended_bounds", "field",
					"fixed_interval", "format", "hard_bounds", "interval", "keyed", "min_doc_count",
					"missing", "offset", "order", "params", "time_zone")),
			Map.entry("range", Set.of("field", "format", "keyed", "missing", "ranges")),
			Map.entry("date_range", Set.of("field", "format", "keyed", "missing", "ranges",
					"time_zone")),
			Map.entry("missing", Set.of("field", "missing")),
			Map.entry("min", Set.of("field", "format", "missing", "value_type")),
			Map.entry("max", Set.of("field", "format", "missing", "value_type")),
			Map.entry("avg", Set.of("field", "format", "missing", "value_type")),
			Map.entry("sum", Set.of("field", "format", "missing")),
			Map.entry("stats", Set.of("field", "format", "missing")),
			Map.entry("extended_stats", Set.of("field", "format", "missing", "sigma")),
			Map.entry("value_count", Set.of("field", "format", "missing")),
			Map.entry("cardinality", Set.of("execution_hint", "field", "missing",
					"precision_threshold")));

	/** Keys allowed on a top-level {@code highlight} block (the {@code fields} map and the nested
	 * {@code highlight_query} are walked specially, not copied verbatim). */
	private static final Set<String> HIGHLIGHT_TOP_KEYS = Set.of("boundary_chars",
			"boundary_max_scan", "boundary_scanner", "boundary_scanner_locale", "encoder",
			"force_source", "fragment_offset", "fragment_size", "fragmenter", "highlight_filter",
			"max_analyzer_offset", "max_fragment_length", "no_match_size", "number_of_fragments",
			"options", "order", "phrase_limit", "post_tags", "pre_tags", "require_field_match",
			"tags_schema", "type");

	/** Keys allowed on a per-field highlight block. */
	private static final Set<String> HIGHLIGHT_FIELD_KEYS = Set.of("boundary_chars",
			"boundary_max_scan", "boundary_scanner", "boundary_scanner_locale", "force_source",
			"fragment_offset", "fragment_size", "fragmenter", "highlight_filter", "matched_fields",
			"max_analyzer_offset", "max_fragment_length", "no_match_size", "number_of_fragments",
			"options", "order", "phrase_limit", "post_tags", "pre_tags", "require_field_match",
			"tags_schema", "type");

	private static final Set<String> COLLAPSE_KEYS = Set.of("field", "inner_hits",
			"max_concurrent_group_searches");

	private static final Set<String> RESCORE_QUERY_KEYS = Set.of("query_weight",
			"rescore_query_weight", "score_mode");

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
	 * keys in {@link #BODY_ALLOWED_KEYS}; reject the
	 * {@code search_after}/{@code from} conflict. The sub-key values are deep-copied here and
	 * re-sanitized in full when each surface is parsed.
	 */
	static ObjectNode sanitizeBodyTopLevel(JsonNode body) {
		scanBodyTopLevelKeys(body);
		return copyAllowlistedKeys(body, BODY_ALLOWED_KEYS, "body", "");
	}

	/** Autocomplete variant of {@link #sanitizeBodyTopLevel}. */
	static ObjectNode sanitizeAutocompleteBodyTopLevel(JsonNode body) {
		scanAutocompleteBodyTopLevelKeys(body);
		return copyAllowlistedKeys(body, AUTOCOMPLETE_BODY_ALLOWED_KEYS, "autocomplete body", "");
	}

	// ---------- query ----------

	/**
	 * Rebuild a query subtree from supported clauses only. When {@code autocomplete} is true the
	 * top-level clause must additionally be one of {@link #ALLOWED_AUTOCOMPLETE_TOP_LEVEL_NAMES}.
	 */
	static JsonNode sanitizeQuery(JsonNode node, boolean autocomplete) {
		requireObject(node, "query");
		if (autocomplete) {
			String kind = singleClauseKind(node, "query");
			if (!ALLOWED_AUTOCOMPLETE_TOP_LEVEL_NAMES.contains(kind)) {
				throw new IllegalArgumentException(
						"autocomplete query top-level clause must be one of "
								+ ALLOWED_AUTOCOMPLETE_TOP_LEVEL_NAMES + "; found '" + kind + "'");
			}
		}
		return copyQueryContainer(node, 1, "query");
	}

	static ObjectNode copyQueryContainer(JsonNode node, int depth, String path) {
		requireObject(node, path);
		if (depth > SearchDslValidator.QUERY_MAX_DEPTH) {
			throw new IllegalArgumentException(
					"query is nested too deeply (max depth " + SearchDslValidator.QUERY_MAX_DEPTH + ")");
		}
		String kind = singleClauseKind(node, path);
		JsonNode body = node.get(kind);
		ObjectNode clean = NODES.objectNode();
		switch (kind) {
		case "bool":
			clean.set(kind, copyBool(body, depth, path + ".bool"));
			break;
		case "dis_max":
			clean.set(kind, copyDisMax(body, depth, path + ".dis_max"));
			break;
		case "constant_score":
			clean.set(kind, copyConstantScore(body, depth, path + ".constant_score"));
			break;
		case "boosting":
			clean.set(kind, copyBoosting(body, depth, path + ".boosting"));
			break;
		case "terms":
			clean.set(kind, copyTerms(body, path + ".terms"));
			break;
		case "match":
		case "match_phrase":
		case "match_phrase_prefix":
		case "match_bool_prefix":
		case "multi_match":
		case "term":
		case "range":
		case "exists":
		case "prefix":
		case "wildcard":
		case "fuzzy":
		case "ids":
		case "simple_query_string":
		case "match_all":
			clean.set(kind, copyLeafQuery(kind, body, path + "." + kind));
			break;
		default:
			throw new IllegalArgumentException("query clause kind is not allowed: '" + kind
					+ "' at " + path + ". Allowed kinds: " + ALLOWED_QUERY_KIND_NAMES);
		}
		return clean;
	}

	static ObjectNode copyLeafQuery(String kind, JsonNode body, String path) {
		requireObject(body, path);
		Set<String> optionKeys = QUERY_LEAF_OPTION_KEYS.get(kind);
		ObjectNode clean = NODES.objectNode();
		if (SHORTHAND_QUERY_KIND_NAMES.contains(kind) && body.size() == 1 && !body.has("field")) {
			// Shorthand: the single key is the field name; the value is the query scalar or a
			// per-field options object whose keys are allowlisted against the long-form options.
			Map.Entry<String, JsonNode> e = body.fields().next();
			JsonNode value = e.getValue();
			if (value.isObject()) {
				clean.set(e.getKey(), copyAllowlistedKeys(value, optionKeys,
						kind + " query options", path + "." + e.getKey()));
			} else {
				clean.set(e.getKey(), value.deepCopy());
			}
			return clean;
		}
		Iterator<Map.Entry<String, JsonNode>> it = body.fields();
		while (it.hasNext()) {
			Map.Entry<String, JsonNode> e = it.next();
			String key = e.getKey();
			if ("field".equals(key) || "fields".equals(key) || optionKeys.contains(key)) {
				clean.set(key, copyOpaque(e.getValue(), path + "." + key));
			} else {
				throw unsupported(kind + " query", key, path);
			}
		}
		return clean;
	}

	/**
	 * A {@code terms} clause is field-keyed: one entry whose key is the field name and whose value
	 * is the inline values array. Reject the cross-index lookup form (an object value) and any key
	 * other than the field plus the allowlisted siblings.
	 */
	static ObjectNode copyTerms(JsonNode body, String path) {
		requireObject(body, path);
		ObjectNode clean = NODES.objectNode();
		String fieldKey = null;
		Iterator<Map.Entry<String, JsonNode>> it = body.fields();
		while (it.hasNext()) {
			Map.Entry<String, JsonNode> e = it.next();
			String key = e.getKey();
			JsonNode value = e.getValue();
			if (TERMS_SIBLING_KEYS.contains(key)) {
				clean.set(key, value.deepCopy());
			} else if (value.isArray() || value.isValueNode()) {
				if (fieldKey != null) {
					throw new IllegalArgumentException("'terms' query may reference only one field; "
							+ "found '" + fieldKey + "' and '" + key + "' at " + path);
				}
				fieldKey = key;
				clean.set(key, copyOpaque(value, path + "." + key));
			} else {
				// An object value under the field name is the terms lookup (cross-index) form.
				throw new IllegalArgumentException(
						"terms lookup form is not allowed (cross-index reference): '" + key + "'");
			}
		}
		return clean;
	}

	static ObjectNode copyBool(JsonNode body, int depth, String path) {
		requireObject(body, path);
		ObjectNode clean = NODES.objectNode();
		Iterator<Map.Entry<String, JsonNode>> it = body.fields();
		while (it.hasNext()) {
			Map.Entry<String, JsonNode> e = it.next();
			String key = e.getKey();
			switch (key) {
			case "must":
			case "should":
			case "must_not":
			case "filter":
				clean.set(key, copyQueryListOrSingle(e.getValue(), depth + 1, path + "." + key));
				break;
			case "_name":
			case "boost":
			case "minimum_should_match":
			case "adjust_pure_negative":
				clean.set(key, e.getValue().deepCopy());
				break;
			default:
				throw unsupported("bool query", key, path);
			}
		}
		return clean;
	}

	static ObjectNode copyDisMax(JsonNode body, int depth, String path) {
		requireObject(body, path);
		ObjectNode clean = NODES.objectNode();
		Iterator<Map.Entry<String, JsonNode>> it = body.fields();
		while (it.hasNext()) {
			Map.Entry<String, JsonNode> e = it.next();
			String key = e.getKey();
			switch (key) {
			case "queries":
				clean.set(key, copyQueryListOrSingle(e.getValue(), depth + 1, path + ".queries"));
				break;
			case "_name":
			case "boost":
			case "tie_breaker":
				clean.set(key, e.getValue().deepCopy());
				break;
			default:
				throw unsupported("dis_max query", key, path);
			}
		}
		return clean;
	}

	static ObjectNode copyConstantScore(JsonNode body, int depth, String path) {
		requireObject(body, path);
		ObjectNode clean = NODES.objectNode();
		Iterator<Map.Entry<String, JsonNode>> it = body.fields();
		while (it.hasNext()) {
			Map.Entry<String, JsonNode> e = it.next();
			String key = e.getKey();
			switch (key) {
			case "filter":
				clean.set(key, copyQueryContainer(e.getValue(), depth + 1, path + ".filter"));
				break;
			case "_name":
			case "boost":
				clean.set(key, e.getValue().deepCopy());
				break;
			default:
				throw unsupported("constant_score query", key, path);
			}
		}
		return clean;
	}

	static ObjectNode copyBoosting(JsonNode body, int depth, String path) {
		requireObject(body, path);
		ObjectNode clean = NODES.objectNode();
		Iterator<Map.Entry<String, JsonNode>> it = body.fields();
		while (it.hasNext()) {
			Map.Entry<String, JsonNode> e = it.next();
			String key = e.getKey();
			switch (key) {
			case "positive":
			case "negative":
				clean.set(key, copyQueryContainer(e.getValue(), depth + 1, path + "." + key));
				break;
			case "_name":
			case "boost":
			case "negative_boost":
				clean.set(key, e.getValue().deepCopy());
				break;
			default:
				throw unsupported("boosting query", key, path);
			}
		}
		return clean;
	}

	static JsonNode copyQueryListOrSingle(JsonNode node, int depth, String path) {
		if (node != null && node.isArray()) {
			ArrayNode out = NODES.arrayNode();
			for (int i = 0; i < node.size(); i++) {
				out.add(copyQueryContainer(node.get(i), depth, path + "[" + i + "]"));
			}
			return out;
		}
		return copyQueryContainer(node, depth, path);
	}

	// ---------- aggregations ----------

	/** Rebuild an aggregations map ({@code name -> AggregationContainer}) from supported kinds. */
	static JsonNode sanitizeAggregations(JsonNode node) {
		return copyAggregationMap(node, "aggregations");
	}

	static ObjectNode copyAggregationMap(JsonNode node, String path) {
		requireObject(node, path);
		ObjectNode clean = NODES.objectNode();
		Iterator<Map.Entry<String, JsonNode>> it = node.fields();
		while (it.hasNext()) {
			Map.Entry<String, JsonNode> e = it.next();
			clean.set(e.getKey(), copyAggregationContainer(e.getValue(), path + "." + e.getKey()));
		}
		return clean;
	}

	static ObjectNode copyAggregationContainer(JsonNode node, String path) {
		requireObject(node, path);
		ObjectNode clean = NODES.objectNode();
		String kind = null;
		Iterator<Map.Entry<String, JsonNode>> it = node.fields();
		while (it.hasNext()) {
			Map.Entry<String, JsonNode> e = it.next();
			String key = e.getKey();
			if ("aggregations".equals(key)) {
				clean.set(key, copyAggregationMap(e.getValue(), path + "." + key));
			} else if ("meta".equals(key)) {
				clean.set(key, copyOpaque(e.getValue(), path + ".meta"));
			} else if (kind != null) {
				throw new IllegalArgumentException("aggregation at " + path
						+ " declares more than one type: '" + kind + "' and '" + key + "'");
			} else {
				kind = key;
				clean.set(key, copyAggregationBody(kind, e.getValue(), path + "." + kind));
			}
		}
		if (kind == null) {
			throw new IllegalArgumentException(
					"aggregation at " + path + " has no recognized aggregation type");
		}
		return clean;
	}

	static ObjectNode copyAggregationBody(String kind, JsonNode body, String path) {
		switch (kind) {
		case "terms":
		case "histogram":
		case "date_histogram":
		case "range":
		case "date_range":
		case "missing":
		case "min":
		case "max":
		case "avg":
		case "sum":
		case "stats":
		case "extended_stats":
		case "value_count":
		case "cardinality":
			return copyAllowlistedKeys(body, AGG_BODY_KEYS.get(kind), kind + " aggregation", path);
		default:
			throw new IllegalArgumentException("aggregation kind is not allowed: '" + kind
					+ "' at " + path + ". Allowed kinds: " + AGG_BODY_KEYS.keySet());
		}
	}

	// ---------- highlight ----------

	/** Rebuild a {@code highlight} block. */
	static JsonNode sanitizeHighlight(JsonNode node) {
		return copyHighlightLevel(node, true, "highlight");
	}

	static ObjectNode copyHighlightLevel(JsonNode node, boolean topLevel, String path) {
		requireObject(node, path);
		Set<String> allowed = topLevel ? HIGHLIGHT_TOP_KEYS : HIGHLIGHT_FIELD_KEYS;
		ObjectNode clean = NODES.objectNode();
		Iterator<Map.Entry<String, JsonNode>> it = node.fields();
		while (it.hasNext()) {
			Map.Entry<String, JsonNode> e = it.next();
			String key = e.getKey();
			if (topLevel && "fields".equals(key)) {
				JsonNode fields = e.getValue();
				requireObject(fields, path + ".fields");
				ObjectNode fieldsClean = NODES.objectNode();
				Iterator<Map.Entry<String, JsonNode>> fit = fields.fields();
				while (fit.hasNext()) {
					Map.Entry<String, JsonNode> fe = fit.next();
					fieldsClean.set(fe.getKey(), copyHighlightLevel(fe.getValue(), false,
							path + ".fields." + fe.getKey()));
				}
				clean.set("fields", fieldsClean);
			} else if ("highlight_query".equals(key)) {
				clean.set(key, copyQueryContainer(e.getValue(), 1, path + ".highlight_query"));
			} else if (allowed.contains(key)) {
				clean.set(key, copyOpaque(e.getValue(), path + "." + key));
			} else {
				throw unsupported("highlight", key, path);
			}
		}
		return clean;
	}

	// ---------- collapse ----------

	/**
	 * Rebuild a {@code collapse} block. {@code inner_hits} is copied through (not silently
	 * dropped) so the typed {@link SearchDslValidator#validateFieldCollapse} can reject it with a
	 * clear message.
	 */
	static JsonNode sanitizeCollapse(JsonNode node) {
		return copyAllowlistedKeys(node, COLLAPSE_KEYS, "collapse", "collapse");
	}

	// ---------- rescore ----------

	/** Rebuild a {@code rescore} stage; the inner {@code rescore_query} is a full query subtree. */
	static JsonNode sanitizeRescore(JsonNode node) {
		requireObject(node, "rescore");
		ObjectNode clean = NODES.objectNode();
		Iterator<Map.Entry<String, JsonNode>> it = node.fields();
		while (it.hasNext()) {
			Map.Entry<String, JsonNode> e = it.next();
			String key = e.getKey();
			if ("window_size".equals(key)) {
				clean.set(key, e.getValue().deepCopy());
			} else if ("query".equals(key)) {
				clean.set(key, copyRescoreQuery(e.getValue(), "rescore.query"));
			} else {
				throw unsupported("rescore", key, path("rescore"));
			}
		}
		return clean;
	}

	static ObjectNode copyRescoreQuery(JsonNode node, String path) {
		requireObject(node, path);
		ObjectNode clean = NODES.objectNode();
		Iterator<Map.Entry<String, JsonNode>> it = node.fields();
		while (it.hasNext()) {
			Map.Entry<String, JsonNode> e = it.next();
			String key = e.getKey();
			if ("rescore_query".equals(key)) {
				clean.set(key, copyQueryContainer(e.getValue(), 1, path + ".rescore_query"));
			} else if (RESCORE_QUERY_KEYS.contains(key)) {
				clean.set(key, e.getValue().deepCopy());
			} else {
				throw unsupported("rescore query", key, path);
			}
		}
		return clean;
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
	 * Deep-copy an opaque leaf value the structural allowlist permits wholesale (a scalar, an
	 * options object such as a {@code highlight.options} map, an
	 * aggregation {@code meta} block, a value array, ...) while rejecting any
	 * {@link #COPY_FORBIDDEN_KEYS} entry encountered anywhere inside it &mdash; an
	 * allowlisted-but-opaque slot can still hide a {@code script}. Bounded by
	 * {@link #FORBIDDEN_SCAN_MAX_DEPTH} so a pathologically nested opaque value can't blow the stack.
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

	/** Return the single clause-kind key of a query container, rejecting empty / multi-key shapes. */
	static String singleClauseKind(JsonNode node, String path) {
		if (node.size() == 0) {
			throw new IllegalArgumentException("a query clause is required at " + path);
		}
		if (node.size() > 1) {
			List<String> keys = new java.util.ArrayList<>();
			Iterator<String> names = node.fieldNames();
			while (names.hasNext()) {
				keys.add(names.next());
			}
			throw new IllegalArgumentException("a query clause at " + path
					+ " must declare exactly one type but found " + keys.size() + ": " + keys);
		}
		return node.fieldNames().next();
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
