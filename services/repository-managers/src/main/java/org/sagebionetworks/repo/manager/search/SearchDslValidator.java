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
import org.opensearch.client.opensearch.core.search.FieldCollapse;
import org.opensearch.client.opensearch.core.search.Highlight;
import org.opensearch.client.opensearch.core.search.HighlightField;
import org.opensearch.client.opensearch.core.search.HighlighterType;
import org.opensearch.client.opensearch.core.search.Rescore;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Phase 2 of the two-phase search-DSL gate: the typed resource-cap validator. It runs <i>after</i>
 * {@link SearchDslSanitizer} has rebuilt the request from an allowlist and the clean node has been
 * deserialized into the typed OpenSearch objects ({@link Query}, {@link Aggregation},
 * {@link Highlight}, {@link FieldCollapse}, {@link Rescore}). Each {@code validate*} method walks
 * its typed object and enforces the numeric caps that need typed accessors and prevent a request
 * from expanding into an unbounded shape inside AOSS: query depth and clause count, aggregation
 * depth and count, value-array length, prefix-expansion, histogram bucket bound, cardinality
 * precision, and the leading-wildcard rejection.
 *
 * <p>This is the defense-in-depth layer behind the sanitizer's structural allowlist. Each
 * {@code walk*} switch has a throwing {@code default}, so a query / aggregation kind that the
 * OpenSearch client adds but nobody wires into the allowlist is rejected here even if it somehow
 * survived the sanitizer.</p>
 *
 * <p>This class also owns the raw-{@link JsonNode} pre-checks on {@code SearchQuery.body} that run
 * <i>before</i> the sanitizer rebuilds and before typed deserialization: the top-level key
 * allowlist ({@link #scanBodyTopLevelKeys} / {@link #scanAutocompleteBodyTopLevelKeys}), the
 * {@code search_after} / {@code from > 0} exclusivity rule, the {@code search_after} shape check
 * ({@link #validateSearchAfterShape}), and the {@code from} / {@code size} resolution
 * ({@link #resolveFrom} / {@link #resolveSize}). These operate on the untyped tree, so they live
 * here with the rest of the request validation rather than in the copy-only sanitizer.</p>
 *
 * <p><b>Resource exhaustion / AOSS denial-of-wallet caps.</b> Depth and total-count caps bound
 * query shape; an inline {@code terms} value array is capped at {@link #MAX_VALUES_PER_CLAUSE};
 * bucket aggregation {@code size} / {@code shard_size} are capped at {@link #MAX_AGG_SIZE};
 * {@code prefix} / {@code wildcard} values that begin with {@code *} or {@code ?} are rejected
 * because a leading wildcard forces a full inverted-index scan.</p>
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

	/**
	 * Maximum value for {@code max_expansions} on the prefix-expansion clauses
	 * ({@code match_phrase_prefix}, {@code match_bool_prefix}, {@code fuzzy}, and
	 * {@code multi_match}). Default is 50 in OpenSearch with no upper bound; an unbounded
	 * value rewrites into many term queries.
	 */
	static final int MAX_PREFIX_EXPANSIONS = 50;

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

	/**
	 * Top-level autocomplete kinds. Narrower than {@link #ALLOWED_QUERY_KINDS} so the
	 * type-ahead surface stays minimal and predictable.
	 */
	static final Set<Query.Kind> ALLOWED_AUTOCOMPLETE_TOP_LEVEL = EnumSet.of(
			Query.Kind.Prefix, Query.Kind.MatchPhrasePrefix, Query.Kind.MatchBoolPrefix);

	private SearchDslValidator() {
	}

	// --------------------------------------------------------------
	// Raw-body pre-checks (top-level keys, pagination, cursor shape).
	// These run on the untyped JsonNode before the sanitizer rebuilds.
	// --------------------------------------------------------------

	/**
	 * Reject any top-level key on {@code SearchQuery.body} outside
	 * {@link SearchDslSanitizer#BODY_ALLOWED_KEYS}. Also rejects {@code search_after} alongside
	 * {@code from > 0} (mutually exclusive).
	 */
	static void scanBodyTopLevelKeys(JsonNode body) {
		if (body == null || !body.isObject()) {
			throw new IllegalArgumentException("body must be a JSON object");
		}
		Iterator<Map.Entry<String, JsonNode>> fields = body.fields();
		while (fields.hasNext()) {
			String key = fields.next().getKey();
			if (!SearchDslSanitizer.BODY_ALLOWED_KEYS.contains(key)) {
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
			if (!SearchDslSanitizer.AUTOCOMPLETE_BODY_ALLOWED_KEYS.contains(key)) {
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

	static void rejectSemanticType(HighlighterType type, String label) {
		if (type == null) {
			return;
		}
		String name = type.isBuiltin() ? type.builtin().jsonValue()
				: type.isCustom() ? type.custom() : null;
		if (FORBIDDEN_HIGHLIGHTER_TYPE_SEMANTIC.equalsIgnoreCase(name)) {
			throw new IllegalArgumentException(label
					+ " 'semantic' is not allowed (requires a deployed ML model)");
		}
	}

	static void checkHighlightCaps(Integer numberOfFragments, Integer fragmentSize, String label) {
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
		if (collapse.field().isEmpty()) {
			throw new IllegalArgumentException("collapse.field is required");
		}
		List<?> innerHits = collapse.innerHits();
		if (!innerHits.isEmpty()) {
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
		int[] count = new int[] { 0 };
		walkQuery(rescore.query().rescoreQuery(), 1, count);
	}

	// --------------------------------------------------------------
	// Query walk.
	// --------------------------------------------------------------

	static void walkQuery(Query query, int depth, int[] count) {
		if (depth > QUERY_MAX_DEPTH) {
			throw new IllegalArgumentException(
					"query is nested too deeply (max depth " + QUERY_MAX_DEPTH + ")");
		}
		if (++count[0] > QUERY_MAX_CLAUSES) {
			throw new IllegalArgumentException(
					"query has too many clauses (max " + QUERY_MAX_CLAUSES + ")");
		}
		// Switch with a throwing default: every allowlisted kind has an explicit case — even the
		// cap-free leaves get an explicit `break` — so a kind that is newly added to the OpenSearch
		// client but never wired here is rejected, not silently passed. This is the typed
		// defense-in-depth layer; SearchDslSanitizer has already rebuilt the tree from the same
		// allowlist.
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
		case Match:
		case MatchPhrase:
		case Term:
		case Range:
		case Exists:
		case MatchAll:
			// Allowlisted leaves with no additional caps to enforce.
			break;
		default:
			throw new IllegalArgumentException("query clause kind is not allowed: '"
					+ query._kind() + "'. Allowed kinds: " + ALLOWED_QUERY_KINDS);
		}
	}

	static void walkBool(BoolQuery bool, int depth, int[] count) {
		walkQueryList(bool.must(), depth + 1, count);
		walkQueryList(bool.should(), depth + 1, count);
		walkQueryList(bool.mustNot(), depth + 1, count);
		walkQueryList(bool.filter(), depth + 1, count);
	}

	static void walkDisMax(DisMaxQuery disMax, int depth, int[] count) {
		List<Query> queries = disMax.queries();
		if (queries.isEmpty()) {
			throw new IllegalArgumentException("'dis_max' clause requires a 'queries' field");
		}
		walkQueryList(queries, depth + 1, count);
	}

	static void walkConstantScore(ConstantScoreQuery constantScore, int depth, int[] count) {
		Query filter = constantScore.filter();
		walkQuery(filter, depth + 1, count);
	}

	static void walkBoosting(BoostingQuery boosting, int depth, int[] count) {
		Query positive = boosting.positive();
		Query negative = boosting.negative();
		walkQuery(positive, depth + 1, count);
		walkQuery(negative, depth + 1, count);
	}

	static void walkQueryList(List<Query> queries, int depth, int[] count) {
		if (queries == null) {
			return;
		}
		for (Query queryClause : queries) {
			walkQuery(queryClause, depth, count);
		}
	}

	// --------------------------------------------------------------
	// Per-kind custom checks.
	// --------------------------------------------------------------

	/**
	 * A {@code terms} clause: reject the cross-index lookup form, cap the inline value array
	 * at {@link #MAX_VALUES_PER_CLAUSE}.
	 */
	static void validateTerms(TermsQuery termsQuery) {
		TermsQueryField terms = termsQuery.terms();
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
	static void validateIds(IdsQuery ids) {
		List<String> values = ids.values();
		if (values.size() > MAX_VALUES_PER_CLAUSE) {
			throw new IllegalArgumentException("ids.values has " + values.size()
					+ " entries; max is " + MAX_VALUES_PER_CLAUSE);
		}
	}

	/**
	 * {@code multi_match}: each {@code fields} entry expands into one internal match clause,
	 * and {@code phrase_prefix} type inherits the {@code max_expansions} concern.
	 */
	static void validateMultiMatch(MultiMatchQuery mm) {
		List<String> fields = mm.fields();
		if (fields.size() > MAX_VALUES_PER_CLAUSE) {
			throw new IllegalArgumentException("multi_match.fields has " + fields.size()
					+ " entries; max is " + MAX_VALUES_PER_CLAUSE);
		}
		checkMaxExpansions(mm.maxExpansions(), "multi_match");
	}

	static void rejectLeadingWildcardPrefix(PrefixQuery prefix) {
		rejectLeadingWildcard(prefix.value(), "prefix", prefix.field());
	}

	static void rejectLeadingWildcardWildcard(WildcardQuery wildcard) {
		String value = wildcard.value() != null ? wildcard.value() : wildcard.wildcard();
		rejectLeadingWildcard(value, "wildcard", wildcard.field());
	}

	static void rejectLeadingWildcard(String pattern, String clause, String field) {
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
	static void validateSimpleQueryString(SimpleQueryStringQuery sq) {
		List<String> fields = sq.fields();
		if (fields.size() > MAX_VALUES_PER_CLAUSE) {
			throw new IllegalArgumentException("simple_query_string.fields has " + fields.size()
					+ " entries; max is " + MAX_VALUES_PER_CLAUSE);
		}
		if (Boolean.TRUE.equals(sq.analyzeWildcard())) {
			String pattern = sq.query();
			if (!pattern.isEmpty()) {
				char first = pattern.charAt(0);
				if (first == '*' || first == '?') {
					throw new IllegalArgumentException(
							"leading wildcard is not allowed in 'simple_query_string.query' "
									+ "with analyze_wildcard=true (forces a full index scan)");
				}
			}
		}
	}

	static void validateFuzzyMaxExpansions(FuzzyQuery fuzzy) {
		checkMaxExpansions(fuzzy.maxExpansions(), "fuzzy");
	}

	static void validateMatchPhrasePrefix(MatchPhrasePrefixQuery mpp) {
		checkMaxExpansions(mpp.maxExpansions(), "match_phrase_prefix");
	}

	static void validateMatchBoolPrefix(MatchBoolPrefixQuery mbp) {
		checkMaxExpansions(mbp.maxExpansions(), "match_bool_prefix");
	}

	static void checkMaxExpansions(Integer value, String clause) {
		if (value != null && value > MAX_PREFIX_EXPANSIONS) {
			throw new IllegalArgumentException("'" + clause + "' max_expansions is "
					+ value + "; max is " + MAX_PREFIX_EXPANSIONS);
		}
	}

	// --------------------------------------------------------------
	// Aggregation walk.
	// --------------------------------------------------------------

	static void walkAggregationMap(Map<String, Aggregation> map, int depth, int[] count) {
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

	static void walkAggregation(Aggregation agg, int depth, int[] count) {
		// Switch with a throwing default, per the same posture as walkQuery: every allowlisted
		// aggregation kind has an explicit case (even the cap-free metric aggregations), so a kind
		// the OpenSearch client adds but nobody wired here is rejected rather than silently passed.
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
		case Missing:
		case Min:
		case Max:
		case Avg:
		case Sum:
		case Stats:
		case ExtendedStats:
		case ValueCount:
			// Allowlisted aggregations with no additional caps to enforce.
			break;
		default:
			throw new IllegalArgumentException("aggregation kind is not allowed: '"
					+ agg._kind() + "'. Allowed kinds: " + ALLOWED_AGGREGATION_KINDS);
		}
		Map<String, Aggregation> subAggregations = agg.aggregations();
		if (!subAggregations.isEmpty()) {
			walkAggregationMap(subAggregations, depth + 1, count);
		}
	}

	/**
	 * A {@code terms} aggregation: cap {@code size} / {@code shard_size} and the
	 * include/exclude term-list lengths. Regex-string {@code include}/{@code exclude} are
	 * passed through (catastrophic regex is bounded by AOSS request timeouts).
	 */
	static void validateTermsAgg(TermsAggregation terms) {
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

	static void validateRangeAgg(RangeAggregation range) {
		List<?> ranges = range.ranges();
		if (ranges.size() > MAX_VALUES_PER_CLAUSE) {
			throw new IllegalArgumentException("'range' aggregation 'ranges' has "
					+ ranges.size() + " entries; max is " + MAX_VALUES_PER_CLAUSE);
		}
	}

	static void validateDateRangeAgg(DateRangeAggregation dateRange) {
		List<?> ranges = dateRange.ranges();
		if (ranges.size() > MAX_VALUES_PER_CLAUSE) {
			throw new IllegalArgumentException("'date_range' aggregation 'ranges' has "
					+ ranges.size() + " entries; max is " + MAX_VALUES_PER_CLAUSE);
		}
	}

	/**
	 * A {@code histogram} aggregation produces (max-min)/interval buckets. Without bounds
	 * the count is unbounded; require either {@code extended_bounds} or {@code hard_bounds},
	 * and require a positive {@code interval}.
	 */
	static void validateHistogramAgg(HistogramAggregation histogram) {
		Double interval = histogram.interval();
		if (interval != null && interval <= 0) {
			throw new IllegalArgumentException("'histogram' interval must be positive");
		}
		if (histogram.extendedBounds() == null && histogram.hardBounds() == null) {
			throw new IllegalArgumentException(
					"'histogram' must specify 'extended_bounds' or 'hard_bounds' to bound bucket count");
		}
	}

	static void validateDateHistogramAgg(DateHistogramAggregation dateHistogram) {
		// date_histogram allows interval / calendar_interval / fixed_interval; the typed
		// model can't easily check positivity for Time-typed intervals, so we focus on the
		// bounds requirement which is what actually caps bucket count.
		if (dateHistogram.extendedBounds() == null && dateHistogram.hardBounds() == null) {
			throw new IllegalArgumentException(
					"'date_histogram' must specify 'extended_bounds' or 'hard_bounds' to bound bucket count");
		}
	}

	static void validateCardinalityAgg(CardinalityAggregation cardinality) {
		Integer precision = cardinality.precisionThreshold();
		if (precision != null && precision > MAX_PRECISION_THRESHOLD) {
			throw new IllegalArgumentException("'cardinality' precision_threshold is "
					+ precision + "; max is " + MAX_PRECISION_THRESHOLD);
		}
	}

	static void checkAggSize(Integer value, String aggType, String key) {
		if (value != null && value > MAX_AGG_SIZE) {
			throw new IllegalArgumentException("'" + aggType + "' aggregation '" + key
					+ "' is " + value + "; max is " + MAX_AGG_SIZE);
		}
	}

}
