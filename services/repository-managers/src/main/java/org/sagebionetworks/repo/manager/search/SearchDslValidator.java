package org.sagebionetworks.repo.manager.search;

import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.opensearch.client.opensearch._types.aggregations.Aggregation;
import org.opensearch.client.opensearch._types.aggregations.CardinalityAggregation;
import org.opensearch.client.opensearch._types.aggregations.DateHistogramAggregation;
import org.opensearch.client.opensearch._types.aggregations.DateRangeAggregation;
import org.opensearch.client.opensearch._types.aggregations.HistogramAggregation;
import org.opensearch.client.opensearch._types.aggregations.RangeAggregation;
import org.opensearch.client.opensearch._types.aggregations.TermsAggregation;
import org.opensearch.client.opensearch._types.aggregations.TermsExclude;
import org.opensearch.client.opensearch._types.aggregations.TermsInclude;
import org.opensearch.client.opensearch._types.query_dsl.BoolQuery;
import org.opensearch.client.opensearch._types.query_dsl.BoostingQuery;
import org.opensearch.client.opensearch._types.query_dsl.ConstantScoreQuery;
import org.opensearch.client.opensearch._types.query_dsl.DisMaxQuery;
import org.opensearch.client.opensearch._types.query_dsl.FuzzyQuery;
import org.opensearch.client.opensearch._types.query_dsl.IdsQuery;
import org.opensearch.client.opensearch._types.query_dsl.MatchBoolPrefixQuery;
import org.opensearch.client.opensearch._types.query_dsl.MatchPhrasePrefixQuery;
import org.opensearch.client.opensearch._types.query_dsl.MultiMatchQuery;
import org.opensearch.client.opensearch._types.query_dsl.PrefixQuery;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch._types.query_dsl.SimpleQueryStringQuery;
import org.opensearch.client.opensearch._types.query_dsl.TermsQuery;
import org.opensearch.client.opensearch._types.query_dsl.TermsQueryField;
import org.opensearch.client.opensearch._types.query_dsl.WildcardQuery;
import org.opensearch.client.opensearch.core.search.CompletionSuggester;
import org.opensearch.client.opensearch.core.search.FieldCollapse;
import org.opensearch.client.opensearch.core.search.FieldSuggester;
import org.opensearch.client.opensearch.core.search.Highlight;
import org.opensearch.client.opensearch.core.search.HighlightField;
import org.opensearch.client.opensearch.core.search.HighlighterType;
import org.opensearch.client.opensearch.core.search.PhraseSuggester;
import org.opensearch.client.opensearch.core.search.Rescore;
import org.opensearch.client.opensearch.core.search.Suggester;
import org.opensearch.client.opensearch.core.search.TermSuggester;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Allowlist validator for the three caller-supplied OpenSearch DSL surfaces Synapse exposes:
 * the query subtree, the aggregations map, and the suggesters map. Each surface validates a
 * typed OpenSearch client object ({@link Query}, {@link Aggregation}, {@link Suggester}) that
 * the caller's JSON has already been deserialized into; the typed deserializer's tagged-union
 * machinery already enforced "single clause type per object" / "no malformed shape", so this
 * class is left with three concerns:
 *
 * <ul>
 *   <li><b>Kind allowlist.</b> Reject any {@code _kind()} that isn't in the allowlist
 *       constants below. Compound clauses recurse into their nested-query slots.</li>
 *   <li><b>Resource caps.</b> Depth, clause count, value-array length, prefix-expansion,
 *       histogram bucket bound, cardinality precision, suggester size — every cap that
 *       prevents a request from expanding into an unbounded shape inside AOSS.</li>
 *   <li><b>Pre-deserialization forbidden-key scan.</b> Properties like {@code script},
 *       {@code script_fields}, {@code indexed_shape}, {@code runtime_mappings}, and
 *       {@code _search_template} are legitimate fields on multiple typed variants the
 *       allowlist permits (e.g. {@code TermsAggregation.script},
 *       {@code DateHistogramAggregation.script}). The typed deserializer would happily bind
 *       them and round-trip to AOSS, so we scan the raw {@link JsonNode} <i>before</i>
 *       deserialization and reject any occurrence anywhere in the body. See
 *       {@link #scanQueryForbiddenKeys(JsonNode)} and friends.</li>
 * </ul>
 *
 * <p><b>Why an allowlist, not a denylist.</b> A denylist must chase every new dangerous or
 * cross-index construct OpenSearch ships. An allowlist is safe-by-default: only the
 * enumerated kinds are permitted, so a newly-introduced kind is rejected until it is
 * deliberately added here after review.</p>
 *
 * <p><b>What this protects against.</b></p>
 * <ul>
 *   <li><b>Script injection</b> &mdash; {@code Script} / {@code ScriptScore} (Painless
 *       execution) are not in the allowlist; a script-bearing property on an otherwise
 *       allowed variant is rejected by the forbidden-key scan.</li>
 *   <li><b>Cross-collection reach</b> &mdash; clause kinds that can reference another index
 *       ({@code MoreLikeThis}, {@code GeoShape}, {@code HasChild}, {@code HasParent},
 *       {@code Percolate}) are not allowlisted; the {@code terms} lookup form is
 *       additionally rejected explicitly since {@code Terms} itself is allowed in its
 *       inline form.</li>
 *   <li><b>Validation bypass</b> &mdash; {@code Wrapper} (a base64-encoded query that would
 *       evade this walk entirely) is not allowlisted.</li>
 *   <li><b>Resource exhaustion / AOSS denial-of-wallet</b> &mdash; depth and total-count caps
 *       bound query shape; an inline {@code terms} value array is capped at
 *       {@link #MAX_VALUES_PER_CLAUSE}; bucket aggregation {@code size} / {@code shard_size}
 *       are capped at {@link #MAX_AGG_SIZE}; {@code prefix} / {@code wildcard} values that
 *       begin with {@code *} or {@code ?} are rejected because a leading wildcard forces a
 *       full inverted-index scan.</li>
 * </ul>
 *
 * <p>Throws {@link IllegalArgumentException} (HTTP 400) on any violation.</p>
 */
final class SearchDslValidator {

	// --------------------------------------------------------------
	// Limits — package-private so tests can reference symbolically.
	// --------------------------------------------------------------

	static final int QUERY_MAX_DEPTH = 20;
	static final int QUERY_MAX_CLAUSES = 256;
	/**
	 * Maximum number of values that may appear in an array carried inside a single clause:
	 * applies to {@code terms} value arrays, {@code ids.values}, {@code multi_match.fields},
	 * and {@code terms} aggregation {@code include}/{@code exclude} arrays. Any of these
	 * would otherwise expand into many internal clauses or buckets and bypass
	 * {@link #QUERY_MAX_CLAUSES} / {@link #MAX_AGG_SIZE}.
	 */
	static final int MAX_VALUES_PER_CLAUSE = 1024;

	static final int AGG_MAX_DEPTH = 10;
	static final int AGG_MAX_COUNT = 100;
	/** Maximum value for {@code size} / {@code shard_size} on bucket aggregations. */
	static final int MAX_AGG_SIZE = 1000;
	/** Maximum {@code precision_threshold} on a {@code cardinality} aggregation. */
	static final int MAX_PRECISION_THRESHOLD = 10000;

	/** Maximum entries in a {@code highlight.fields} map. Each entry is one highlighted column. */
	static final int MAX_HIGHLIGHT_FIELDS = 50;
	/** Maximum {@code number_of_fragments} on a highlight field (top-level or per-field). AOSS default is 5. */
	static final int MAX_HIGHLIGHT_FRAGMENTS = 100;
	/** Maximum {@code fragment_size} on a highlight field, in characters. AOSS default is 100. */
	static final int MAX_HIGHLIGHT_FRAGMENT_SIZE = 1000;
	/**
	 * Built-in highlighter type name we reject upfront. The {@code semantic} highlighter
	 * requires a deployed ML model the Synapse stack does not provision; AOSS would error
	 * server-side anyway, so reject with a clear 400 before round-tripping.
	 */
	static final String FORBIDDEN_HIGHLIGHTER_TYPE_SEMANTIC = "semantic";

	/** Maximum value for {@code max_concurrent_group_searches} on a {@code collapse} block. */
	static final int MAX_COLLAPSE_CONCURRENT_GROUP_SEARCHES = 10;

	/** Maximum value for {@code window_size} on a {@code rescore} stage. */
	static final int MAX_RESCORE_WINDOW_SIZE = 1000;

	static final int SUGGEST_MAX_COUNT = 50;
	/**
	 * Maximum nesting depth inside a suggester definition. Today no suggester body recurses,
	 * so this is mostly a guarantee against future code paths that add nesting.
	 */
	static final int SUGGEST_MAX_DEPTH = 3;
	/** Maximum {@code size} on a suggester definition. */
	static final int MAX_SUGGESTER_SIZE = 100;
	/**
	 * Maximum value for {@code max_expansions} on the prefix-expansion clauses
	 * ({@code match_phrase_prefix}, {@code match_bool_prefix}, {@code fuzzy}, and
	 * {@code multi_match}). Default is 50 in OpenSearch with no upper bound; an unbounded
	 * value rewrites into many term queries.
	 */
	static final int MAX_PREFIX_EXPANSIONS = 50;
	/**
	 * Maximum recursion depth for the forbidden-key scan. Independent of the per-surface
	 * depth caps, which only bound clause/agg structure — leaf bodies can still be arbitrarily
	 * nested JSON, and an unbounded recursive scan would blow the stack on a pathological input.
	 */
	static final int FORBIDDEN_SCAN_MAX_DEPTH = 100;

	// --------------------------------------------------------------
	// Kind allowlists.
	// --------------------------------------------------------------

	/**
	 * Query kinds a caller may use. Compound kinds recurse into their nested query slots;
	 * everything not listed here is rejected. Intentionally excludes script-bearing
	 * ({@code Script}, {@code ScriptScore}, {@code FunctionScore}), cross-index
	 * ({@code MoreLikeThis}, {@code GeoShape}, {@code HasChild}, {@code HasParent},
	 * {@code Percolate}), and validation-bypassing ({@code Wrapper}) kinds.
	 */
	static final Set<Query.Kind> ALLOWED_QUERY_KINDS = EnumSet.of(
			// leaf
			Query.Kind.Match, Query.Kind.MultiMatch, Query.Kind.MatchPhrase,
			Query.Kind.MatchPhrasePrefix, Query.Kind.MatchBoolPrefix,
			Query.Kind.Term, Query.Kind.Terms, Query.Kind.Range, Query.Kind.Exists,
			Query.Kind.Prefix, Query.Kind.Wildcard, Query.Kind.Fuzzy, Query.Kind.Ids,
			Query.Kind.SimpleQueryString, Query.Kind.MatchAll,
			// compound
			Query.Kind.Bool, Query.Kind.DisMax, Query.Kind.ConstantScore, Query.Kind.Boosting);

	/**
	 * Aggregation kinds a caller may use. Bucket aggregations may carry nested
	 * sub-aggregations via {@code aggregations()}. Intentionally excludes
	 * {@code ScriptedMetric}, pipeline aggregations (e.g. {@code BucketScript},
	 * {@code BucketSelector}), and the {@code Filter}/{@code Filters} aggregations (whose
	 * bodies are queries that would need separate query-DSL validation).
	 */
	static final Set<Aggregation.Kind> ALLOWED_AGGREGATION_KINDS = EnumSet.of(
			// bucket
			Aggregation.Kind.Terms, Aggregation.Kind.Histogram, Aggregation.Kind.DateHistogram,
			Aggregation.Kind.Range, Aggregation.Kind.DateRange, Aggregation.Kind.Missing,
			// metric
			Aggregation.Kind.Min, Aggregation.Kind.Max, Aggregation.Kind.Avg,
			Aggregation.Kind.Sum, Aggregation.Kind.Stats, Aggregation.Kind.ExtendedStats,
			Aggregation.Kind.ValueCount, Aggregation.Kind.Cardinality);

	/** Suggester kinds a caller may use. */
	static final Set<FieldSuggester.Kind> ALLOWED_SUGGESTER_KINDS = EnumSet.of(
			FieldSuggester.Kind.Term, FieldSuggester.Kind.Phrase, FieldSuggester.Kind.Completion);

	/**
	 * Top-level autocomplete kinds. Narrower than {@link #ALLOWED_QUERY_KINDS} so the
	 * type-ahead surface stays minimal and predictable.
	 */
	static final Set<Query.Kind> ALLOWED_AUTOCOMPLETE_TOP_LEVEL = EnumSet.of(
			Query.Kind.Prefix, Query.Kind.MatchPhrasePrefix, Query.Kind.MatchBoolPrefix);

	// --------------------------------------------------------------
	// Forbidden-key sets — applied as a JsonNode pre-pass.
	// --------------------------------------------------------------

	private static final Set<String> QUERY_FORBIDDEN_KEYS = Set.of(
			"script", "indexed_shape", "runtime_mappings", "script_fields", "_search_template");

	private static final Set<String> AGGREGATION_FORBIDDEN_KEYS = Set.of(
			"script", "indexed_shape");

	private static final Set<String> SUGGEST_FORBIDDEN_KEYS = Set.of("script");

	private static final Set<String> HIGHLIGHT_FORBIDDEN_KEYS = Set.of("script", "indexed_shape");

	private static final Set<String> COLLAPSE_FORBIDDEN_KEYS = Set.of("script");

	// --------------------------------------------------------------
	// Top-level body allowlists.
	// --------------------------------------------------------------

	/**
	 * Allowlisted top-level keys on {@code SearchQuery.body}. Anything else is rejected
	 * with HTTP 400. The strict allowlist replaces the typed-schema rejection that the
	 * old structured SearchQuery carried natively.
	 */
	static final Set<String> BODY_ALLOWED_KEYS = Set.of(
			"query", "post_filter",
			"aggregations", "aggs",
			"suggest", "highlight", "collapse", "rescore",
			"sort", "_source",
			"from", "size", "search_after");

	/**
	 * Narrow allowlist for {@code SearchAutocompleteRequest.body}. The dropdown surface has
	 * no aggregations / suggest / sort / pagination — only a prefix-flavored {@code query}
	 * and an optional {@code _source} filter.
	 */
	static final Set<String> AUTOCOMPLETE_BODY_ALLOWED_KEYS = Set.of("query", "_source");

	private SearchDslValidator() {
	}

	// --------------------------------------------------------------
	// Top-level body validation.
	// --------------------------------------------------------------

	/**
	 * Reject any top-level key on {@code SearchQuery.body} outside {@link #BODY_ALLOWED_KEYS}.
	 * Also rejects supplying both {@code aggregations} and {@code aggs} on the same body
	 * (they're aliases; pick one), and rejects {@code search_after} alongside {@code from > 0}
	 * (mutually exclusive).
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
		if (body.has("aggregations") && body.has("aggs")) {
			throw new IllegalArgumentException(
					"body has both 'aggregations' and 'aggs'; supply only one");
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

	// --------------------------------------------------------------
	// Pre-deserialization forbidden-key scan.
	// --------------------------------------------------------------

	/**
	 * Scan a query subtree for forbidden keys. Run before {@link Query#_DESERIALIZER}, since
	 * the typed deserializer would silently bind {@code script} onto e.g.
	 * {@code FunctionScoreQuery.script_score} or onto a non-allowlisted variant we never
	 * reach with the typed kind check.
	 */
	static void scanQueryForbiddenKeys(JsonNode root) {
		scanForbiddenKeys(root, QUERY_FORBIDDEN_KEYS, "query", 0);
	}

	/**
	 * Scan an aggregations subtree for forbidden keys. Critical because multiple allowed
	 * aggregation kinds (e.g. {@link TermsAggregation}, {@link RangeAggregation},
	 * {@link DateHistogramAggregation}) define {@code script} as a real bindable property
	 * &mdash; without this scan a caller could send a Painless script straight to AOSS.
	 */
	static void scanAggregationsForbiddenKeys(JsonNode root) {
		scanForbiddenKeys(root, AGGREGATION_FORBIDDEN_KEYS, "aggregation", 0);
	}

	/** Scan a suggesters subtree for forbidden keys. */
	static void scanSuggestForbiddenKeys(JsonNode root) {
		scanForbiddenKeys(root, SUGGEST_FORBIDDEN_KEYS, "suggester", 0);
	}

	/**
	 * Scan a highlight subtree for forbidden keys. {@code script} can appear inside a
	 * {@code highlight_query} body or on the typed-deserializer-bindable
	 * {@code options} map; {@code indexed_shape} is rejected for the same reason it is
	 * elsewhere.
	 */
	static void scanHighlightForbiddenKeys(JsonNode root) {
		scanForbiddenKeys(root, HIGHLIGHT_FORBIDDEN_KEYS, "highlight", 0);
	}

	/** Scan a collapse subtree for forbidden keys. */
	static void scanCollapseForbiddenKeys(JsonNode root) {
		scanForbiddenKeys(root, COLLAPSE_FORBIDDEN_KEYS, "collapse", 0);
	}

	private static void scanForbiddenKeys(JsonNode node, Set<String> forbiddenKeys,
			String surface, int depth) {
		if (node == null) {
			return;
		}
		if (depth > FORBIDDEN_SCAN_MAX_DEPTH) {
			throw new IllegalArgumentException(
					"forbidden-key scan exceeded maximum depth (" + FORBIDDEN_SCAN_MAX_DEPTH + ")");
		}
		if (node.isObject()) {
			Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
			while (fields.hasNext()) {
				Map.Entry<String, JsonNode> field = fields.next();
				if (forbiddenKeys.contains(field.getKey())) {
					throw new IllegalArgumentException(
							"forbidden key in " + surface + ": '" + field.getKey() + "'");
				}
				scanForbiddenKeys(field.getValue(), forbiddenKeys, surface, depth + 1);
			}
		} else if (node.isArray()) {
			for (JsonNode element : node) {
				scanForbiddenKeys(element, forbiddenKeys, surface, depth + 1);
			}
		}
	}

	// --------------------------------------------------------------
	// Public façades — typed validation.
	// --------------------------------------------------------------

	/**
	 * Validate a query-DSL subtree. When {@code autocomplete} is true, additionally requires
	 * the top-level kind to be one of {@link #ALLOWED_AUTOCOMPLETE_TOP_LEVEL}.
	 */
	static void validateQuery(Query query, boolean autocomplete) {
		if (query == null) {
			throw new IllegalArgumentException("query must not be null");
		}
		if (autocomplete && !ALLOWED_AUTOCOMPLETE_TOP_LEVEL.contains(query._kind())) {
			throw new IllegalArgumentException(
					"autocomplete query top-level clause must be one of "
							+ ALLOWED_AUTOCOMPLETE_TOP_LEVEL + "; found '" + query._kind() + "'");
		}
		int[] count = new int[] { 0 };
		walkQuery(query, 1, count);
	}

	/** Validate an aggregations map &mdash; aggregation name to {@link Aggregation}. */
	static void validateAggregations(Map<String, Aggregation> aggregations) {
		if (aggregations == null) {
			throw new IllegalArgumentException("aggregations must not be null");
		}
		int[] count = new int[] { 0 };
		walkAggregationMap(aggregations, 1, count);
	}

	/**
	 * Validate a {@link Highlight} block. Enforces the field-count cap, per-field
	 * fragment / fragment-size caps, and rejects the {@code semantic} highlighter type.
	 * Any nested {@code highlight_query} (top-level or per-field) is recursively validated
	 * against the same query allowlist as the main {@code SearchQuery.query}.
	 */
	static void validateHighlight(Highlight highlight) {
		if (highlight == null) {
			throw new IllegalArgumentException("highlight must not be null");
		}
		rejectSemanticType(highlight.type(), "highlight.type");
		checkHighlightCaps(highlight.numberOfFragments(), highlight.fragmentSize(), "highlight");
		if (highlight.highlightQuery() != null) {
			int[] count = new int[] { 0 };
			walkQuery(highlight.highlightQuery(), 1, count);
		}
		Map<String, HighlightField> fields = highlight.fields();
		if (fields != null) {
			if (fields.size() > MAX_HIGHLIGHT_FIELDS) {
				throw new IllegalArgumentException("highlight.fields has " + fields.size()
						+ " entries; max is " + MAX_HIGHLIGHT_FIELDS);
			}
			for (Map.Entry<String, HighlightField> entry : fields.entrySet()) {
				HighlightField hf = entry.getValue();
				String label = "highlight.fields[" + entry.getKey() + "]";
				rejectSemanticType(hf.type(), label + ".type");
				checkHighlightCaps(hf.numberOfFragments(), hf.fragmentSize(), label);
				if (hf.highlightQuery() != null) {
					int[] count = new int[] { 0 };
					walkQuery(hf.highlightQuery(), 1, count);
				}
			}
		}
	}

	private static void rejectSemanticType(HighlighterType type, String label) {
		if (type == null) {
			return;
		}
		if (type.isBuiltin()) {
			String jsonValue = type.builtin().jsonValue();
			if (FORBIDDEN_HIGHLIGHTER_TYPE_SEMANTIC.equalsIgnoreCase(jsonValue)) {
				throw new IllegalArgumentException(label
						+ " 'semantic' is not allowed (requires a deployed ML model)");
			}
		} else if (type.isCustom()) {
			String custom = type.custom();
			if (FORBIDDEN_HIGHLIGHTER_TYPE_SEMANTIC.equalsIgnoreCase(custom)) {
				throw new IllegalArgumentException(label
						+ " 'semantic' is not allowed (requires a deployed ML model)");
			}
		}
	}

	private static void checkHighlightCaps(Integer numberOfFragments, Integer fragmentSize, String label) {
		if (numberOfFragments != null && numberOfFragments > MAX_HIGHLIGHT_FRAGMENTS) {
			throw new IllegalArgumentException(label + ".number_of_fragments is "
					+ numberOfFragments + "; max is " + MAX_HIGHLIGHT_FRAGMENTS);
		}
		if (fragmentSize != null && fragmentSize > MAX_HIGHLIGHT_FRAGMENT_SIZE) {
			throw new IllegalArgumentException(label + ".fragment_size is "
					+ fragmentSize + "; max is " + MAX_HIGHLIGHT_FRAGMENT_SIZE);
		}
	}

	/**
	 * Validate a {@link FieldCollapse} block. Rejects {@code inner_hits} (the per-group hit
	 * lists are not surfaced on {@code SearchQueryResults}) and caps
	 * {@code max_concurrent_group_searches}.
	 */
	static void validateFieldCollapse(FieldCollapse collapse) {
		if (collapse == null) {
			throw new IllegalArgumentException("collapse must not be null");
		}
		if (collapse.field() == null || collapse.field().isEmpty()) {
			throw new IllegalArgumentException("collapse.field is required");
		}
		List<?> innerHits = collapse.innerHits();
		if (innerHits != null && !innerHits.isEmpty()) {
			throw new IllegalArgumentException(
					"collapse.inner_hits is not supported (per-group hits are not surfaced on SearchQueryResults)");
		}
		Integer concurrent = collapse.maxConcurrentGroupSearches();
		if (concurrent != null && concurrent > MAX_COLLAPSE_CONCURRENT_GROUP_SEARCHES) {
			throw new IllegalArgumentException("collapse.max_concurrent_group_searches is "
					+ concurrent + "; max is " + MAX_COLLAPSE_CONCURRENT_GROUP_SEARCHES);
		}
	}

	/**
	 * Validate a {@link Rescore} stage. Caps {@code window_size} and recursively validates the
	 * inner {@code rescore_query} subtree against the same allowlist as the top-level
	 * {@code SearchQuery.query}.
	 */
	static void validateRescore(Rescore rescore) {
		if (rescore == null) {
			throw new IllegalArgumentException("rescore must not be null");
		}
		Integer windowSize = rescore.windowSize();
		if (windowSize != null && windowSize > MAX_RESCORE_WINDOW_SIZE) {
			throw new IllegalArgumentException("rescore.window_size is " + windowSize
					+ "; max is " + MAX_RESCORE_WINDOW_SIZE);
		}
		if (rescore.query() == null || rescore.query().rescoreQuery() == null) {
			throw new IllegalArgumentException("rescore.query.rescore_query is required");
		}
		int[] count = new int[] { 0 };
		walkQuery(rescore.query().rescoreQuery(), 1, count);
	}

	/** Validate a {@link Suggester}. */
	static void validateSuggester(Suggester suggester) {
		if (suggester == null) {
			throw new IllegalArgumentException("suggest must not be null");
		}
		Map<String, FieldSuggester> suggesters = suggester.suggesters();
		if (suggesters.size() > SUGGEST_MAX_COUNT) {
			throw new IllegalArgumentException("too many suggesters (max " + SUGGEST_MAX_COUNT + ")");
		}
		for (FieldSuggester fs : suggesters.values()) {
			validateFieldSuggester(fs, 1);
		}
	}

	// --------------------------------------------------------------
	// Query walk.
	// --------------------------------------------------------------

	private static void walkQuery(Query query, int depth, int[] count) {
		if (depth > QUERY_MAX_DEPTH) {
			throw new IllegalArgumentException(
					"query is nested too deeply (max depth " + QUERY_MAX_DEPTH + ")");
		}
		if (!ALLOWED_QUERY_KINDS.contains(query._kind())) {
			throw new IllegalArgumentException("query clause kind is not allowed: '"
					+ query._kind() + "'. Allowed kinds: " + ALLOWED_QUERY_KINDS);
		}
		if (++count[0] > QUERY_MAX_CLAUSES) {
			throw new IllegalArgumentException(
					"query has too many clauses (max " + QUERY_MAX_CLAUSES + ")");
		}
		switch (query._kind()) {
		case Bool:
			walkBool(query.bool(), depth, count);
			break;
		case DisMax:
			walkDisMax(query.disMax(), depth, count);
			break;
		case ConstantScore:
			walkConstantScore(query.constantScore(), depth, count);
			break;
		case Boosting:
			walkBoosting(query.boosting(), depth, count);
			break;
		case Terms:
			validateTerms(query.terms());
			break;
		case Ids:
			validateIds(query.ids());
			break;
		case MultiMatch:
			validateMultiMatch(query.multiMatch());
			break;
		case Prefix:
			rejectLeadingWildcardPrefix(query.prefix());
			break;
		case Wildcard:
			rejectLeadingWildcardWildcard(query.wildcard());
			break;
		case SimpleQueryString:
			validateSimpleQueryString(query.simpleQueryString());
			break;
		case Fuzzy:
			validateFuzzyMaxExpansions(query.fuzzy());
			break;
		case MatchPhrasePrefix:
			validateMatchPhrasePrefix(query.matchPhrasePrefix());
			break;
		case MatchBoolPrefix:
			validateMatchBoolPrefix(query.matchBoolPrefix());
			break;
		default:
			// Match, MatchPhrase, Term, Range, Exists, MatchAll — no caps to enforce.
			break;
		}
	}

	private static void walkBool(BoolQuery bool, int depth, int[] count) {
		walkQueryList(bool.must(), depth + 1, count);
		walkQueryList(bool.should(), depth + 1, count);
		walkQueryList(bool.mustNot(), depth + 1, count);
		walkQueryList(bool.filter(), depth + 1, count);
	}

	private static void walkDisMax(DisMaxQuery disMax, int depth, int[] count) {
		List<Query> queries = disMax.queries();
		if (queries == null || queries.isEmpty()) {
			throw new IllegalArgumentException("'dis_max' clause requires a 'queries' field");
		}
		walkQueryList(queries, depth + 1, count);
	}

	private static void walkConstantScore(ConstantScoreQuery constantScore, int depth, int[] count) {
		Query filter = constantScore.filter();
		if (filter == null) {
			throw new IllegalArgumentException("'constant_score' clause requires a 'filter' field");
		}
		walkQuery(filter, depth + 1, count);
	}

	private static void walkBoosting(BoostingQuery boosting, int depth, int[] count) {
		Query positive = boosting.positive();
		Query negative = boosting.negative();
		if (positive == null) {
			throw new IllegalArgumentException("'boosting' clause requires a 'positive' field");
		}
		if (negative == null) {
			throw new IllegalArgumentException("'boosting' clause requires a 'negative' field");
		}
		walkQuery(positive, depth + 1, count);
		walkQuery(negative, depth + 1, count);
	}

	private static void walkQueryList(List<Query> queries, int depth, int[] count) {
		if (queries == null) {
			return;
		}
		for (Query q : queries) {
			walkQuery(q, depth, count);
		}
	}

	// --------------------------------------------------------------
	// Per-kind custom checks.
	// --------------------------------------------------------------

	/**
	 * A {@code terms} clause: reject the cross-index lookup form, cap the inline value array
	 * at {@link #MAX_VALUES_PER_CLAUSE}.
	 */
	private static void validateTerms(TermsQuery termsQuery) {
		TermsQueryField terms = termsQuery.terms();
		if (terms == null) {
			return;
		}
		if (terms._kind() == TermsQueryField.Kind.Lookup) {
			throw new IllegalArgumentException(
					"terms lookup form is not allowed (cross-index reference): '"
							+ termsQuery.field() + "'");
		}
		List<?> values = terms.value();
		if (values != null && values.size() > MAX_VALUES_PER_CLAUSE) {
			throw new IllegalArgumentException("terms array for field '" + termsQuery.field()
					+ "' has " + values.size() + " values; max is " + MAX_VALUES_PER_CLAUSE);
		}
	}

	/** {@code ids.values} carries the same expansion risk as a {@code terms} array. */
	private static void validateIds(IdsQuery ids) {
		List<String> values = ids.values();
		if (values != null && values.size() > MAX_VALUES_PER_CLAUSE) {
			throw new IllegalArgumentException("ids.values has " + values.size()
					+ " entries; max is " + MAX_VALUES_PER_CLAUSE);
		}
	}

	/**
	 * {@code multi_match}: each {@code fields} entry expands into one internal match clause,
	 * and {@code phrase_prefix} type inherits the {@code max_expansions} concern.
	 */
	private static void validateMultiMatch(MultiMatchQuery mm) {
		List<String> fields = mm.fields();
		if (fields != null && fields.size() > MAX_VALUES_PER_CLAUSE) {
			throw new IllegalArgumentException("multi_match.fields has " + fields.size()
					+ " entries; max is " + MAX_VALUES_PER_CLAUSE);
		}
		checkMaxExpansions(mm.maxExpansions(), "multi_match");
	}

	private static void rejectLeadingWildcardPrefix(PrefixQuery prefix) {
		rejectLeadingWildcard(prefix.value(), "prefix", prefix.field());
	}

	private static void rejectLeadingWildcardWildcard(WildcardQuery wildcard) {
		String value = wildcard.value() != null ? wildcard.value() : wildcard.wildcard();
		rejectLeadingWildcard(value, "wildcard", wildcard.field());
	}

	private static void rejectLeadingWildcard(String pattern, String clause, String field) {
		if (pattern == null || pattern.isEmpty()) {
			return;
		}
		char first = pattern.charAt(0);
		if (first == '*' || first == '?') {
			throw new IllegalArgumentException("leading wildcard is not allowed in '" + clause
					+ "' on field '" + field + "' (forces a full index scan)");
		}
	}

	/**
	 * {@code simple_query_string}: cap {@code fields} length, reject a leading wildcard in
	 * {@code query} when {@code analyze_wildcard} is true (otherwise the leading wildcard
	 * wouldn't actually be evaluated as one). The mini-DSL inside {@code query} is otherwise
	 * passed through &mdash; AOSS request timeouts bound the worst case.
	 */
	private static void validateSimpleQueryString(SimpleQueryStringQuery sq) {
		List<String> fields = sq.fields();
		if (fields != null && fields.size() > MAX_VALUES_PER_CLAUSE) {
			throw new IllegalArgumentException("simple_query_string.fields has " + fields.size()
					+ " entries; max is " + MAX_VALUES_PER_CLAUSE);
		}
		if (Boolean.TRUE.equals(sq.analyzeWildcard())) {
			String pattern = sq.query();
			if (pattern != null && !pattern.isEmpty()) {
				char first = pattern.charAt(0);
				if (first == '*' || first == '?') {
					throw new IllegalArgumentException(
							"leading wildcard is not allowed in 'simple_query_string.query' "
									+ "with analyze_wildcard=true (forces a full index scan)");
				}
			}
		}
	}

	private static void validateFuzzyMaxExpansions(FuzzyQuery fuzzy) {
		checkMaxExpansions(fuzzy.maxExpansions(), "fuzzy");
	}

	private static void validateMatchPhrasePrefix(MatchPhrasePrefixQuery mpp) {
		checkMaxExpansions(mpp.maxExpansions(), "match_phrase_prefix");
	}

	private static void validateMatchBoolPrefix(MatchBoolPrefixQuery mbp) {
		checkMaxExpansions(mbp.maxExpansions(), "match_bool_prefix");
	}

	private static void checkMaxExpansions(Integer value, String clause) {
		if (value != null && value > MAX_PREFIX_EXPANSIONS) {
			throw new IllegalArgumentException("'" + clause + "' max_expansions is "
					+ value + "; max is " + MAX_PREFIX_EXPANSIONS);
		}
	}

	// --------------------------------------------------------------
	// Aggregation walk.
	// --------------------------------------------------------------

	private static void walkAggregationMap(Map<String, Aggregation> map, int depth, int[] count) {
		if (depth > AGG_MAX_DEPTH) {
			throw new IllegalArgumentException(
					"aggregations are nested too deeply (max depth " + AGG_MAX_DEPTH + ")");
		}
		for (Map.Entry<String, Aggregation> entry : map.entrySet()) {
			if (++count[0] > AGG_MAX_COUNT) {
				throw new IllegalArgumentException(
						"too many aggregations (max " + AGG_MAX_COUNT + ")");
			}
			walkAggregation(entry.getValue(), depth, count);
		}
	}

	private static void walkAggregation(Aggregation agg, int depth, int[] count) {
		if (!ALLOWED_AGGREGATION_KINDS.contains(agg._kind())) {
			throw new IllegalArgumentException("aggregation kind is not allowed: '"
					+ agg._kind() + "'. Allowed kinds: " + ALLOWED_AGGREGATION_KINDS);
		}
		switch (agg._kind()) {
		case Terms:
			validateTermsAgg(agg.terms());
			break;
		case Range:
			validateRangeAgg(agg.range());
			break;
		case DateRange:
			validateDateRangeAgg(agg.dateRange());
			break;
		case Histogram:
			validateHistogramAgg(agg.histogram());
			break;
		case DateHistogram:
			validateDateHistogramAgg(agg.dateHistogram());
			break;
		case Cardinality:
			validateCardinalityAgg(agg.cardinality());
			break;
		default:
			break;
		}
		Map<String, Aggregation> sub = agg.aggregations();
		if (sub != null && !sub.isEmpty()) {
			walkAggregationMap(sub, depth + 1, count);
		}
	}

	/**
	 * A {@code terms} aggregation: cap {@code size} / {@code shard_size} and the
	 * include/exclude term-list lengths. Regex-string {@code include}/{@code exclude} are
	 * passed through (catastrophic regex is bounded by AOSS request timeouts).
	 */
	private static void validateTermsAgg(TermsAggregation terms) {
		checkAggSize(terms.size(), "terms", "size");
		checkAggSize(terms.shardSize(), "terms", "shard_size");
		TermsInclude include = terms.include();
		if (include != null && include.isTerms()) {
			List<String> list = include.terms();
			if (list != null && list.size() > MAX_VALUES_PER_CLAUSE) {
				throw new IllegalArgumentException("'terms' aggregation 'include' has "
						+ list.size() + " entries; max is " + MAX_VALUES_PER_CLAUSE);
			}
		}
		TermsExclude exclude = terms.exclude();
		if (exclude != null && exclude.isTerms()) {
			List<String> list = exclude.terms();
			if (list != null && list.size() > MAX_VALUES_PER_CLAUSE) {
				throw new IllegalArgumentException("'terms' aggregation 'exclude' has "
						+ list.size() + " entries; max is " + MAX_VALUES_PER_CLAUSE);
			}
		}
	}

	private static void validateRangeAgg(RangeAggregation range) {
		List<?> ranges = range.ranges();
		if (ranges != null && ranges.size() > MAX_VALUES_PER_CLAUSE) {
			throw new IllegalArgumentException("'range' aggregation 'ranges' has "
					+ ranges.size() + " entries; max is " + MAX_VALUES_PER_CLAUSE);
		}
	}

	private static void validateDateRangeAgg(DateRangeAggregation dateRange) {
		List<?> ranges = dateRange.ranges();
		if (ranges != null && ranges.size() > MAX_VALUES_PER_CLAUSE) {
			throw new IllegalArgumentException("'date_range' aggregation 'ranges' has "
					+ ranges.size() + " entries; max is " + MAX_VALUES_PER_CLAUSE);
		}
	}

	/**
	 * A {@code histogram} aggregation produces (max-min)/interval buckets. Without bounds
	 * the count is unbounded; require either {@code extended_bounds} or {@code hard_bounds},
	 * and require a positive {@code interval}.
	 */
	private static void validateHistogramAgg(HistogramAggregation histogram) {
		Double interval = histogram.interval();
		if (interval != null && interval <= 0) {
			throw new IllegalArgumentException("'histogram' interval must be positive");
		}
		if (histogram.extendedBounds() == null && histogram.hardBounds() == null) {
			throw new IllegalArgumentException(
					"'histogram' must specify 'extended_bounds' or 'hard_bounds' to bound bucket count");
		}
	}

	private static void validateDateHistogramAgg(DateHistogramAggregation dateHistogram) {
		// date_histogram allows interval / calendar_interval / fixed_interval; the typed
		// model can't easily check positivity for Time-typed intervals, so we focus on the
		// bounds requirement which is what actually caps bucket count.
		if (dateHistogram.extendedBounds() == null && dateHistogram.hardBounds() == null) {
			throw new IllegalArgumentException(
					"'date_histogram' must specify 'extended_bounds' or 'hard_bounds' to bound bucket count");
		}
	}

	private static void validateCardinalityAgg(CardinalityAggregation cardinality) {
		Integer precision = cardinality.precisionThreshold();
		if (precision != null && precision > MAX_PRECISION_THRESHOLD) {
			throw new IllegalArgumentException("'cardinality' precision_threshold is "
					+ precision + "; max is " + MAX_PRECISION_THRESHOLD);
		}
	}

	private static void checkAggSize(Integer value, String aggType, String key) {
		if (value != null && value > MAX_AGG_SIZE) {
			throw new IllegalArgumentException("'" + aggType + "' aggregation '" + key
					+ "' is " + value + "; max is " + MAX_AGG_SIZE);
		}
	}

	// --------------------------------------------------------------
	// Suggester walk.
	// --------------------------------------------------------------

	private static void validateFieldSuggester(FieldSuggester fs, int depth) {
		if (depth > SUGGEST_MAX_DEPTH) {
			throw new IllegalArgumentException(
					"suggesters are nested too deeply (max depth " + SUGGEST_MAX_DEPTH + ")");
		}
		if (!ALLOWED_SUGGESTER_KINDS.contains(fs._kind())) {
			throw new IllegalArgumentException("suggester kind is not allowed: '"
					+ fs._kind() + "'. Allowed kinds: " + ALLOWED_SUGGESTER_KINDS);
		}
		Integer size;
		switch (fs._kind()) {
		case Term:
			TermSuggester term = fs.term();
			size = term.size();
			break;
		case Phrase:
			PhraseSuggester phrase = fs.phrase();
			size = phrase.size();
			break;
		case Completion:
			CompletionSuggester completion = fs.completion();
			size = completion.size();
			break;
		default:
			size = null;
			break;
		}
		if (size != null && size > MAX_SUGGESTER_SIZE) {
			throw new IllegalArgumentException("'" + fs._kind() + "' suggester 'size' is "
					+ size + "; max is " + MAX_SUGGESTER_SIZE);
		}
	}
}
