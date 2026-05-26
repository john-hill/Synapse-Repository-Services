package org.sagebionetworks.repo.manager.search;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.ErrorCause;
import org.opensearch.client.opensearch._types.FieldSort;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch._types.Refresh;
import org.opensearch.client.opensearch._types.ShardSearchFailure;
import org.opensearch.client.opensearch._types.ShardStatistics;
import org.opensearch.client.opensearch._types.SortOptions;
import org.opensearch.client.opensearch._types.SortOrder;
import org.opensearch.client.opensearch._types.aggregations.Aggregation;
import org.opensearch.client.opensearch._types.aggregations.LongTermsBucketKey;
import org.opensearch.client.opensearch._types.analysis.Analyzer;
import org.opensearch.client.opensearch._types.analysis.CharFilter;
import org.opensearch.client.opensearch._types.analysis.CustomAnalyzer;
import org.opensearch.client.opensearch._types.analysis.TokenFilter;
import org.opensearch.client.opensearch._types.analysis.Tokenizer;
import org.opensearch.client.opensearch._types.mapping.DynamicMapping;
import org.opensearch.client.opensearch._types.mapping.Property;
import org.opensearch.client.opensearch._types.query_dsl.BoolQuery;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch._types.query_dsl.TextQueryType;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.CountRequest;
import org.opensearch.client.opensearch.core.CountResponse;
import org.opensearch.client.opensearch.core.DeleteRequest;
import org.opensearch.client.opensearch.core.IndexRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem;
import org.opensearch.client.opensearch.core.search.HighlightField;
import org.opensearch.client.opensearch.core.search.Hit;
import org.opensearch.client.opensearch.indices.AnalyzeRequest;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.opensearch.client.opensearch.indices.CreateIndexResponse;
import org.opensearch.client.opensearch.indices.IndexSettingsAnalysis;
import org.sagebionetworks.repo.model.search.SearchFieldValue;
import org.sagebionetworks.repo.model.search.SearchHit;
import org.sagebionetworks.repo.model.search.SearchQuery;
import org.sagebionetworks.repo.model.search.SearchQueryPart;
import org.sagebionetworks.repo.model.search.SearchQueryResults;
import org.sagebionetworks.repo.model.search.SortDirection;
import org.sagebionetworks.repo.model.search.SortField;
import org.sagebionetworks.repo.model.search.table.ColumnAnalyzerOverride;
import org.sagebionetworks.repo.model.search.table.ColumnAnalyzerOverrideEntry;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.util.RetryException;
import org.sagebionetworks.util.TimeUtils;
import org.sagebionetworks.util.ValidateArgument;
import org.sagebionetworks.workers.util.aws.message.RecoverableMessageException;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Wraps the OpenSearch Java client for all AOSS index lifecycle (create / delete /
 * writability probe), bulk-document indexing, and query execution (search and autocomplete).
 * Static helpers (component-reference rewriting, error classification, bulk-failure message
 * building) are package-private for unit testing.
 *
 * <p>Each {@link #createIndex} call receives a map of qualified-name &rarr; resolved analyzer
 * JSON; the manager merges every analyzer's owned components and analyzer entries into a
 * single OpenSearch {@code settings.analysis} block, namespacing each owned component as
 * {@code {aossKey}__{localName}} so multiple TextAnalyzers can reuse the same local name
 * without collision.</p>
 */
@Service
public class OpenSearchManagerImpl implements OpenSearchManager {

	private static final Logger LOG = LogManager.getLogger(OpenSearchManagerImpl.class);

	private static final int HTTP_TOO_MANY_REQUESTS = 429;
	private static final int HTTP_INTERNAL_SERVER_ERROR = 500;
	private static final int HTTP_MAX_SERVER_ERROR = 599;

	// Per-item bulk-failure descriptors are logged in full, but the first N are also
	// embedded in the thrown RuntimeException message so the reason reaches the user
	// via SEARCH_INDEX_STATUS.ERROR_MESSAGE (VARCHAR(3000)) and ASYNCH_JOB_STATUS.
	static final int MAX_FAILURE_SAMPLES = 5;
	static final int MAX_BULK_ERROR_MESSAGE_CHARS = 2500;
	static final String TRUNCATION_MARKER = "...[truncated]";

	// Intra-batch retry for transient AOSS rejections (429 / 5xx on a subset of items).
	// Resubmit only the incomplete subset with exponential backoff so the batch can drain
	// instead of bouncing the whole change message back to SQS on every partial failure.
	// Non-final so unit tests can lower the values and avoid real-wall-clock sleeps.
	static int BULK_INDEX_MAX_RETRIES = 10;
	static long BULK_INDEX_INITIAL_BACKOFF_MS = 10000L;

	// Readiness probe for a freshly-created index. AOSS acknowledges createIndex and is
	// queryable before shards are ready to accept writes, so the only reliable probe is
	// an actual write. Same budget as the bulk-index retry for consistency.
	static int INDEX_WRITABLE_MAX_RETRIES = 10;
	static long INDEX_WRITABLE_INITIAL_BACKOFF_MS = 10000L;

	// Retry budget for synchronous AOSS validate-analyzer-settings calls. The cluster-level
	// _analyze endpoint occasionally returns transient index_not_found_exception or 5xx /
	// network errors; absorb those before surfacing the error so curators don't see flaky
	// 400s on legitimate analyzers.
	static int VALIDATE_MAX_RETRIES = 10;
	static long VALIDATE_INITIAL_BACKOFF_MS = 1000L;

	// Convergence probe for a freshly-finished bulk index. AOSS acknowledges bulk writes
	// before the documents are visible to _search or _count, so we poll _count until the
	// index reports the expected number of documents. Same budget as the writability probe
	// for consistency. Non-final so unit tests can lower the values and avoid real sleeps.
	static int COUNT_PROBE_MAX_RETRIES = 10;
	static long COUNT_PROBE_INITIAL_BACKOFF_MS = 10000L;

	// Cleanup retry for the readiness-probe sentinel. AOSS doesn't honor refresh=wait_for,
	// so a single delete that fails on a transient network blip would orphan the sentinel
	// (visible only to MATCH_ALL queries since _row_id = -1 cannot collide with real ids,
	// but still undesirable). Tighter budget than the readiness probe itself because any
	// orphan is non-fatal — the next probe overwrites the same id, so we trade a small
	// number of extra attempts for liveness.
	static int SENTINEL_CLEANUP_MAX_RETRIES = 5;
	static long SENTINEL_CLEANUP_INITIAL_BACKOFF_MS = 1000L;

	static final String READINESS_PROBE_DOC_ID = "__readiness_probe__";

	private static final String SYSTEM_FIELD_ROW_ID = "_row_id";
	private static final String SYSTEM_FIELD_ROW_VERSION = "_row_version";
	private static final String SUB_FIELD_KEYWORD = "keyword";
	private static final String INDEX_NOT_FOUND_EXCEPTION = "index_not_found_exception";
	// AOSS reports a concurrent index-delete attempt with a reason text containing
	// "concurrent deletes". Package-visible so callers can recognize and translate
	// it into a recoverable SQS retry.
	static final String CONCURRENT_DELETES_MARKER = "concurrent deletes";
	/**
	 * OpenSearch reserved analyzer name. The SearchConfiguration's primary TextAnalyzer
	 * must declare an entry here; it lands at the index's
	 * {@code analysis.analyzer.default} slot (see
	 * <a href="https://docs.opensearch.org/latest/analyzers/index-analyzers/">OpenSearch
	 * index analyzers</a>) and is also the analyzer that override TextAnalyzers' field
	 * mappings bind to when referenced by qualified name.
	 */
	static final String DEFAULT_ANALYZER_NAME = "default";

	/**
	 * OpenSearch reserved analyzer name for asymmetric search. When the SearchConfiguration's
	 * primary TextAnalyzer declares an entry at this key, it lands at the index's
	 * {@code analysis.analyzer.default_search} slot (see
	 * <a href="https://docs.opensearch.org/latest/analyzers/search-analyzers/">OpenSearch
	 * search analyzers</a>).
	 */
	static final String DEFAULT_SEARCH_ANALYZER_NAME = "default_search";

	/**
	 * Translate a Synapse qualified name (e.g. {@code org.sagebionetworks-SCIENTIFIC}) to a
	 * settings key safe to emit under {@code analysis.analyzer} / {@code analysis.filter} /
	 * {@code analysis.tokenizer}. AOSS treats {@code .} in these keys as a JSON path
	 * separator and rejects the index, so dots are encoded at the wire boundary.
	 *
	 * <p>The encoding uses the literal sequence {@value #DOT_ENCODING} rather than a single
	 * underscore so the mapping is bijective: qnames that legitimately contain underscores
	 * (e.g. {@code org_sage-A_B}) cannot collide with qnames that contain dots
	 * (e.g. {@code org.sage-A.B}).</p>
	 */
	static final String DOT_ENCODING = "__dot__";

	static String toAossKey(String qualifiedName) {
		return qualifiedName == null ? null : qualifiedName.replace(".", DOT_ENCODING);
	}

	/** True when the OpenSearch error is AOSS's "concurrent deletes" rejection. */
	static boolean isConcurrentDeleteError(OpenSearchException e) {
		String reason = e.error() == null ? null : e.error().reason();
		return reason != null && reason.contains(CONCURRENT_DELETES_MARKER);
	}

	private static final int DEFAULT_LIMIT = 25;
	private static final int MAX_LIMIT = 100;
	private static final int AUTOCOMPLETE_MAX_LIMIT = 8;
	private static final int DEFAULT_FACET_SIZE = 25;

	private final OpenSearchClient openSearchClient;

	public OpenSearchManagerImpl(OpenSearchClient openSearchClient) {
		this.openSearchClient = openSearchClient;
	}

	@Override
	public Optional<String> createIndex(String indexName, List<ColumnModel> columns,
			String defaultAnalyzer,
			List<ColumnAnalyzerOverride> columnAnalyzerOverrides,
			Map<String, IndexSettingsAnalysis> resolvedAnalyzers) {
		ValidateArgument.required(resolvedAnalyzers, "resolvedAnalyzers");

		Map<String, String> nameToId = columns.stream()
				.collect(Collectors.toMap(ColumnModel::getName, ColumnModel::getId, (a2, b) -> a2));
		Map<String, ColumnAnalyzerOverrideEntry> overrideMap = buildOverrideMap(columnAnalyzerOverrides, nameToId);

		try {
			CreateIndexRequest request = CreateIndexRequest.of(req -> req
					.index(indexName)
					.settings(s -> s.analysis(a -> {
						buildAnalysisSettings(a, resolvedAnalyzers, defaultAnalyzer);
						return a;
					}))
					.mappings(m -> {
						buildMappings(m, columns, defaultAnalyzer,
								overrideMap, resolvedAnalyzers);
						return m;
					})
			);

			String appliedConfigJson = request.toJsonString();
			CreateIndexResponse response = openSearchClient.indices().create(request);

			if (!Boolean.TRUE.equals(response.acknowledged())) {
				throw new IllegalStateException("Search index " + indexName + " creation was not acknowledged.");
			}

			return Optional.of(appliedConfigJson);
		} catch (OpenSearchException e) {
			if ("resource_already_exists_exception".equals(e.error().type())) {
				return Optional.empty();
			}
			throw new RuntimeException("Failed to create search index: " + indexName
					+ " (" + describeError(e.error()) + ")", e);
		} catch (IOException e) {
			throw new RuntimeException("Failed to create search index: " + indexName, e);
		}
	}

	/**
	 * Build the OpenSearch index's {@code settings.analysis} block from every TextAnalyzer
	 * the SearchConfiguration references.
	 *
	 * <p><b>Why this is non-trivial:</b> a single AOSS index has exactly ONE
	 * {@code settings.analysis} block, but a SearchConfiguration may pull in many
	 * TextAnalyzers (the configuration's primary analyzer plus the analyzers referenced
	 * by per-column overrides). All of their components (char filters, tokenizers,
	 * token filters, analyzers) must coexist in that one block. Two TextAnalyzers can
	 * legitimately each declare a local component named {@code "english_stop"} — to
	 * prevent collision, every TextAnalyzer's owned components are namespaced by the
	 * TextAnalyzer's qualified name when written to AOSS. See {@link #registerAnalyzer}
	 * for the detailed namespacing scheme.</p>
	 *
	 * <p><b>Reserved-name promotion:</b> the TextAnalyzer pointed at by
	 * {@code defaultAnalyzerQname} is the configuration's primary. Its
	 * {@value #DEFAULT_ANALYZER_NAME} entry is registered at the bare reserved key
	 * {@value #DEFAULT_ANALYZER_NAME} (so OpenSearch picks it up at
	 * {@code analysis.analyzer.default}); if it also declares
	 * {@value #DEFAULT_SEARCH_ANALYZER_NAME}, that lands at the reserved
	 * {@value #DEFAULT_SEARCH_ANALYZER_NAME} key. All other TextAnalyzers' analyzer
	 * entries (including any they may name {@code default}) are registered only under
	 * their namespaced keys; they remain reachable through the bare-qname alias used by
	 * field mappings, but never claim the reserved index-wide slots.</p>
	 *
	 * @param a                    AOSS analysis-builder being populated.
	 * @param resolvedAnalyzers    qualified-name &rarr; typed analysis settings (post-
	 *                             {@code SearchOpaqueJsonUtil.resolveAnalyzerSettings}).
	 * @param defaultAnalyzerQname qualified name of the SearchConfiguration's primary
	 *                             TextAnalyzer, or {@code null} if the SearchConfiguration
	 *                             does not set one.
	 */
	private void buildAnalysisSettings(IndexSettingsAnalysis.Builder a,
			Map<String, IndexSettingsAnalysis> resolvedAnalyzers, String defaultAnalyzerQname) {
		for (Map.Entry<String, IndexSettingsAnalysis> entry : resolvedAnalyzers.entrySet()) {
			boolean isPrimary = entry.getKey().equals(defaultAnalyzerQname);
			registerAnalyzer(a, entry.getKey(), entry.getValue(), isPrimary);
		}
	}

	/**
	 * Register one TextAnalyzer's components and analyzer entries into the shared AOSS
	 * {@code settings.analysis} block, namespaced by the TextAnalyzer's qualified name so
	 * names declared by other TextAnalyzers in the same index can't collide.
	 *
	 * <p><b>Naming scheme:</b></p>
	 * <ul>
	 *   <li>The TextAnalyzer's qualified name {@code {organizationName}-{name}} is encoded
	 *       to an AOSS-safe key by {@link #toAossKey} (AOSS treats {@code .} as a
	 *       JSON-path separator inside settings keys).</li>
	 *   <li>Every component (char filter, tokenizer, token filter) declared inside this
	 *       TextAnalyzer's registry maps is registered in AOSS under
	 *       {@code {aossKey}__{localName}}.</li>
	 *   <li>Every entry inside this TextAnalyzer's {@code analyzer} map is registered the
	 *       same way: {@code {aossKey}__{localName}}.</li>
	 *   <li>The entry named {@value #DEFAULT_ANALYZER_NAME} is also registered under the
	 *       bare {@code aossKey} as an alias. Field mappings (built by {@link #buildMappings})
	 *       bind to this bare-qname form so they don't have to know about the inner
	 *       analyzer-name layout.</li>
	 *   <li><b>Primary only:</b> when {@code isPrimary} is true, the entry named
	 *       {@value #DEFAULT_ANALYZER_NAME} is additionally registered under the bare
	 *       reserved key {@value #DEFAULT_ANALYZER_NAME} so OpenSearch picks it up at
	 *       {@code analysis.analyzer.default}. If the TextAnalyzer also declares
	 *       {@value #DEFAULT_SEARCH_ANALYZER_NAME}, that entry is similarly promoted to
	 *       the reserved {@value #DEFAULT_SEARCH_ANALYZER_NAME} key.</li>
	 * </ul>
	 *
	 * <p><b>Reference rewriting:</b> when a {@link CustomAnalyzer} entry references one of
	 * its own owned components by name (e.g. {@code filter: ["lowercase", "english_stop"]}
	 * where {@code english_stop} is declared in the same TextAnalyzer's filter registry),
	 * that reference is rewritten to the namespaced form so it points at the registered
	 * component. Built-in and plugin names ({@code "lowercase"}, {@code "standard"}, etc.)
	 * are not owned by any TextAnalyzer and pass through verbatim.</p>
	 */
	private void registerAnalyzer(IndexSettingsAnalysis.Builder a, String qname,
			IndexSettingsAnalysis settings, boolean isPrimary) {
		String aossKey = toAossKey(qname);
		// The chain-rewrite step needs the owned-name sets to decide which references to
		// namespace. Both reads and the typed maps default to empty when absent.
		Set<String> ownedCharFilters = settings.charFilter().keySet();
		Set<String> ownedTokenizers = settings.tokenizer().keySet();
		Set<String> ownedFilters = settings.filter().keySet();

		// Register each owned component under {aossKey}__{localName} via the typed builders.
		settings.charFilter().forEach((name, def) -> a.charFilter(aossKey + "__" + name, def));
		settings.tokenizer().forEach((name, def) -> a.tokenizer(aossKey + "__" + name, def));
		settings.filter().forEach((name, def) -> a.filter(aossKey + "__" + name, def));

		// A TextAnalyzer with no analyzer entries is structurally legal (e.g. a registry-
		// only resource), but won't be reachable from a SearchConfiguration. Nothing more
		// to do for this TextAnalyzer in that case.
		Map<String, Analyzer> analyzers = settings.analyzer();
		if (analyzers.isEmpty()) {
			return;
		}
		// Register each analyzer entry. CustomAnalyzer entries have their tokenizer /
		// filter / char_filter chains rewritten so any reference to one of THIS
		// TextAnalyzer's owned components points at the namespaced registry key.
		analyzers.forEach((localName, analyzer) -> {
			Analyzer rewritten = rewriteOwnedReferences(analyzer, aossKey,
					ownedCharFilters, ownedFilters, ownedTokenizers);
			a.analyzer(aossKey + "__" + localName, rewritten);
			if (DEFAULT_ANALYZER_NAME.equals(localName)) {
				// Bare-qname alias for the canonical "default" analyzer. Field mappings bind
				// by the bare qname, so this alias is what makes the TextAnalyzer reachable
				// from defaultAnalyzer / ColumnAnalyzerOverride.
				a.analyzer(aossKey, rewritten);
				if (isPrimary) {
					// Promote the configuration's primary analyzer to OpenSearch's reserved
					// `default` slot — picked up at analysis.analyzer.default.
					a.analyzer(DEFAULT_ANALYZER_NAME, rewritten);
				}
			} else if (isPrimary && DEFAULT_SEARCH_ANALYZER_NAME.equals(localName)) {
				// Promote the configuration's primary analyzer's `default_search` entry to
				// OpenSearch's reserved `default_search` slot — picked up at
				// analysis.analyzer.default_search at search time.
				a.analyzer(DEFAULT_SEARCH_ANALYZER_NAME, rewritten);
			}
		});
	}

	/**
	 * If {@code analyzer} is the {@code custom} variant, rebuild it with each
	 * tokenizer / filter / char_filter chain element that names a locally-owned component
	 * rewritten to its namespaced registry key. Built-ins and plugin names pass through
	 * unchanged. Non-custom analyzer variants ({@code keyword}, {@code standard}, etc.)
	 * never reference local registry components and are returned as-is.
	 *
	 * <p>The OpenSearch Java client's list-typed builders ({@code filter(List)},
	 * {@code charFilter(List)}) are <i>additive</i> &mdash; they append to whatever the
	 * source builder already holds, so {@code toBuilder()} cannot be used here. Construct
	 * a fresh {@link CustomAnalyzer} instead and copy the scalar fields explicitly.</p>
	 */
	static Analyzer rewriteOwnedReferences(Analyzer analyzer, String aossKey,
			Set<String> ownedCharFilters, Set<String> ownedFilters, Set<String> ownedTokenizers) {
		if (!analyzer.isCustom()) {
			return analyzer;
		}
		CustomAnalyzer source = analyzer.custom();
		List<String> charFilterChain = rewriteChain(source.charFilter(), aossKey, ownedCharFilters);
		List<String> filterChain = rewriteChain(source.filter(), aossKey, ownedFilters);
		String tokenizer = source.tokenizer();
		String rewrittenTokenizer = (tokenizer != null && ownedTokenizers.contains(tokenizer))
				? aossKey + "__" + tokenizer : tokenizer;
		CustomAnalyzer rebuilt = CustomAnalyzer.of(b -> {
			if (rewrittenTokenizer != null) {
				b.tokenizer(rewrittenTokenizer);
			}
			if (charFilterChain != null && !charFilterChain.isEmpty()) {
				b.charFilter(charFilterChain);
			}
			if (filterChain != null && !filterChain.isEmpty()) {
				b.filter(filterChain);
			}
			if (source.positionIncrementGap() != null) {
				b.positionIncrementGap(source.positionIncrementGap());
			}
			if (source.positionOffsetGap() != null) {
				b.positionOffsetGap(source.positionOffsetGap());
			}
			return b;
		});
		return Analyzer.of(b -> b.custom(rebuilt));
	}

	private static List<String> rewriteChain(List<String> chain, String aossKey, Set<String> owned) {
		if (chain == null || chain.isEmpty()) {
			return chain;
		}
		return chain.stream()
				.map(name -> owned.contains(name) ? aossKey + "__" + name : name)
				.collect(Collectors.toList());
	}

	/**
	 * Build the OpenSearch index's field mappings. The per-column analyzer is whichever
	 * TextAnalyzer wins precedence (override &gt; column-type default == primary &gt; column-type
	 * default differs); when that TextAnalyzer declares an {@code analyzer.default_search}
	 * entry, the field's {@code search_analyzer} is bound to its namespaced registry key so
	 * asymmetric search-time analysis applies regardless of which lever pulled the analyzer
	 * in.
	 */
	private void buildMappings(org.opensearch.client.opensearch._types.mapping.TypeMapping.Builder m,
			List<ColumnModel> columns, String defaultAnalyzerQname,
			Map<String, ColumnAnalyzerOverrideEntry> overrideMap,
			Map<String, IndexSettingsAnalysis> resolvedAnalyzers) {
		Set<String> registeredAnalyzerQnames = resolvedAnalyzers.keySet();
		m.properties(SYSTEM_FIELD_ROW_ID, p -> p.long_(l -> l));
		m.properties(SYSTEM_FIELD_ROW_VERSION, p -> p.long_(l -> l));

		for (ColumnModel column : columns) {
			String columnId = column.getId();
			ColumnType columnType = column.getColumnType();

			ColumnAnalyzerOverrideEntry override = overrideMap.get(columnId);

			// Per-column resolution. See the per-bullet rules in the method javadoc above.
			String effectiveQname;
			if (override != null) {
				effectiveQname = SearchOpaqueJsonUtil.readRef(override.getAnalyzer());
			} else {
				String typeDefault = ColumnTypeToOpenSearchMapping.getDefaultAnalyzerQualifiedName(columnType);
				if (typeDefault == null || typeDefault.equals(defaultAnalyzerQname)) {
					effectiveQname = null;
				} else {
					effectiveQname = typeDefault;
				}
			}

			if (effectiveQname != null) {
				ValidateArgument.requirement(registeredAnalyzerQnames.contains(effectiveQname),
						"analyzer '" + effectiveQname + "' for column " + columnId
								+ " was not registered.");
			}

			boolean hasDefaultSearch = effectiveQname != null
					&& analyzerDeclaresDefaultSearch(resolvedAnalyzers, effectiveQname);

			m.properties(columnId, buildProperty(columnType, effectiveQname, hasDefaultSearch));
		}
	}

	private static boolean analyzerDeclaresDefaultSearch(Map<String, IndexSettingsAnalysis> resolvedAnalyzers,
			String qname) {
		IndexSettingsAnalysis resolved = resolvedAnalyzers.get(qname);
		return resolved != null && resolved.analyzer().containsKey(DEFAULT_SEARCH_ANALYZER_NAME);
	}

	@Override
	public void deleteIndex(String indexName) {
		try {
			openSearchClient.indices().delete(req -> req.index(indexName));
		} catch (OpenSearchException e) {
			if (INDEX_NOT_FOUND_EXCEPTION.equals(e.error().type())) {
				return;
			}
			// Concurrent deletes: rethrow the OpenSearchException (a RuntimeException)
			// unwrapped so callers can recognize this case via isConcurrentDeleteError
			// and translate to a recoverable SQS retry.
			if (isConcurrentDeleteError(e)) {
				throw e;
			}
			throw new RuntimeException("Failed to delete search index: " + indexName
					+ " (" + describeError(e.error()) + ")", e);
		} catch (IOException e) {
			throw new RuntimeException("Failed to delete search index: " + indexName, e);
		}
	}

	@Override
	public void waitForIndexWritable(String indexName) throws RecoverableMessageException {
		// Fixed sentinel id so repeated probe writes on the same index overwrite the same
		// document rather than accumulating copies — one delete at the end is sufficient.
		Map<String, Object> sentinel = new HashMap<>();
		sentinel.put(SYSTEM_FIELD_ROW_ID, -1L);
		sentinel.put(SYSTEM_FIELD_ROW_VERSION, -1L);
		final int[] attempt = {0};
		try {
			TimeUtils.waitForExponentialMaxRetry(INDEX_WRITABLE_MAX_RETRIES, INDEX_WRITABLE_INITIAL_BACKOFF_MS,
					() -> {
						attempt[0]++;
						try {
							openSearchClient.index(IndexRequest.of(r -> r
									.index(indexName)
									.id(READINESS_PROBE_DOC_ID)
									.document(sentinel)));
							return Boolean.TRUE;
						} catch (OpenSearchException e) {
							LOG.warn("Index {} not yet writable (attempt {}/{}): {}",
									indexName, attempt[0], INDEX_WRITABLE_MAX_RETRIES, describeError(e.error()));
							throw new RetryException(e);
						} catch (IOException e) {
							LOG.warn("Index {} not yet writable (attempt {}/{}): {}",
									indexName, attempt[0], INDEX_WRITABLE_MAX_RETRIES, e.getMessage());
							throw new RetryException(e);
						}
					});
		} catch (RetryException e) {
			LOG.error("Index {} did not accept writes after {} attempts", indexName, INDEX_WRITABLE_MAX_RETRIES);
			throw new RecoverableMessageException(
					"AOSS index " + indexName + " did not accept writes within the retry budget",
					e.getCause());
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException("Failed readiness probe for search index: " + indexName, e);
		}
		// Remove the sentinel so real indexing never observes it. AOSS doesn't honor
		// `refresh=wait_for`, so a single delete call that fails on a network blip leaves
		// the sentinel behind — visible to MATCH_ALL queries until the next probe overwrites
		// it. Retry the delete a few times before giving up. The eventual swallow is still
		// non-fatal: _row_id = -1 cannot collide with real row ids, so the orphan is at most
		// a stale row in MATCH_ALL output, not an indexing-correctness defect.
		final int[] cleanupAttempt = {0};
		try {
			TimeUtils.waitForExponentialMaxRetry(SENTINEL_CLEANUP_MAX_RETRIES, SENTINEL_CLEANUP_INITIAL_BACKOFF_MS,
					() -> {
						cleanupAttempt[0]++;
						try {
							openSearchClient.delete(DeleteRequest.of(r -> r
									.index(indexName)
									.id(READINESS_PROBE_DOC_ID)));
							return Boolean.TRUE;
						} catch (OpenSearchException e) {
							LOG.warn("Sentinel cleanup failed for index {} (attempt {}/{}): {}",
									indexName, cleanupAttempt[0], SENTINEL_CLEANUP_MAX_RETRIES,
									describeError(e.error()));
							throw new RetryException(e);
						} catch (IOException e) {
							LOG.warn("Sentinel cleanup failed for index {} (attempt {}/{}): {}",
									indexName, cleanupAttempt[0], SENTINEL_CLEANUP_MAX_RETRIES, e.getMessage());
							throw new RetryException(e);
						}
					});
		} catch (Exception e) {
			LOG.warn("Failed to delete readiness probe document from index {} after {} attempts: {}",
					indexName, SENTINEL_CLEANUP_MAX_RETRIES, e.getMessage());
		}
	}

	@Override
	public void waitForDocumentCount(String indexName, long expectedCount) throws RecoverableMessageException {
		// Empty indexes report zero immediately; skip the round-trip.
		if (expectedCount <= 0L) {
			return;
		}
		final int[] attempt = {0};
		final long[] lastObserved = {-1L};
		try {
			TimeUtils.waitForExponentialMaxRetry(COUNT_PROBE_MAX_RETRIES, COUNT_PROBE_INITIAL_BACKOFF_MS,
					() -> {
						attempt[0]++;
						try {
							CountResponse response = openSearchClient.count(CountRequest.of(r -> r
									.index(indexName)));
							long actual = response.count();
							lastObserved[0] = actual;
							// >= rather than == so a leftover readiness-probe sentinel cannot
							// permanently strand convergence one short.
							if (actual >= expectedCount) {
								return Boolean.TRUE;
							}
							LOG.warn("Index {} not yet converged (attempt {}/{}): {} of {} documents visible",
									indexName, attempt[0], COUNT_PROBE_MAX_RETRIES, actual, expectedCount);
							throw new RetryException("count " + actual + " of " + expectedCount);
						} catch (OpenSearchException e) {
							LOG.warn("Index {} count probe failed (attempt {}/{}): {}",
									indexName, attempt[0], COUNT_PROBE_MAX_RETRIES, describeError(e.error()));
							throw new RetryException(e);
						} catch (IOException e) {
							LOG.warn("Index {} count probe failed (attempt {}/{}): {}",
									indexName, attempt[0], COUNT_PROBE_MAX_RETRIES, e.getMessage());
							throw new RetryException(e);
						}
					});
		} catch (RetryException e) {
			LOG.error("Index {} did not converge to expected count after {} attempts ({} of {})",
					indexName, COUNT_PROBE_MAX_RETRIES, lastObserved[0], expectedCount);
			throw new RecoverableMessageException(
					"AOSS index " + indexName + " did not converge to expected count ("
							+ lastObserved[0] + " of " + expectedCount + ") within the retry budget",
					e.getCause());
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException("Failed convergence probe for search index: " + indexName, e);
		}
	}

	@Override
	public long bulkIndex(String indexName, List<BulkOperation> operations) {
		// Callers must hand us idempotent ops (index/delete with explicit _id). When a
		// transport failure drops the response of a partially-successful envelope, the
		// retry resubmits ops that may have already landed; idempotent ops absorb that
		// without writing duplicates. See OpenSearchManager.bulkIndex javadoc.
		if (operations.isEmpty()) {
			return 0L;
		}
		final int totalOps = operations.size();
		// Ops still needing to be submitted. Attempt 1 sends the whole list as one bulk request;
		// later attempts iterate this list and send each op as its own single-op bulk request
		// so a single slow shard can't reject the whole batch.
		final List<BulkOperation> incomplete = new ArrayList<>(operations);
		// Most recent classification's retryable items — used for final ERROR-per-item diagnostics
		// when retries are exhausted.
		final List<BulkResponseItem> lastRetryable = new ArrayList<>();
		final int[] attempt = {0};

		try {
			TimeUtils.waitForExponentialMaxRetry(BULK_INDEX_MAX_RETRIES, BULK_INDEX_INITIAL_BACKOFF_MS, () -> {
				attempt[0]++;
				runAttempt(indexName, totalOps, attempt[0], incomplete, lastRetryable);
				return Boolean.TRUE;
			});
			return totalOps;
		} catch (RetryException e) {
			for (BulkResponseItem item : lastRetryable) {
				LOG.error("Bulk index item failed in {}: {}", indexName, describeBulkItemFailure(item));
			}
			throw new RecoverableMessageException(String.format(
					"Bulk index to %s failed after %d attempts: %d document(s) still retryable out of %d",
					indexName, BULK_INDEX_MAX_RETRIES, incomplete.size(), totalOps),
					e.getCause());
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException("Failed to bulk index to search index: " + indexName, e);
		}
	}

	/**
	 * Runs one retry iteration. Attempt 1 submits {@code incomplete} as a single bulk request;
	 * later attempts submit each op in {@code incomplete} as its own single-op bulk request.
	 * Mutates {@code incomplete} and {@code lastRetryable} in place to reflect the set of ops
	 * still incomplete after this attempt. Returns normally when everything has been indexed, throws
	 * {@link RuntimeException} on any permanent failure, or throws {@link RetryException} so
	 * {@link TimeUtils#waitForExponentialMaxRetry} backs off and invokes us again.
	 */
	private void runAttempt(String indexName, int totalOps, int attempt,
			List<BulkOperation> incomplete, List<BulkResponseItem> lastRetryable) throws RetryException {
		List<BulkResponseItem> items;
		List<BulkOperation> opsSubmitted;
		if (attempt == 1) {
			BulkResponse response = executeBulk(indexName, incomplete);
			if (!response.errors()) {
				incomplete.clear();
				lastRetryable.clear();
				return;
			}
			items = response.items();
			opsSubmitted = new ArrayList<>(incomplete);
			incomplete.clear();
		} else {
			// Per-op: remove each op from `incomplete` only AFTER executeBulk returns. If
			// executeBulk throws an envelope RetryException mid-iteration, the current op + any
			// not-yet-iterated ops remain in `incomplete` so the next attempt resubmits them
			// without reprocessing the ops that already landed.
			items = new ArrayList<>(incomplete.size());
			opsSubmitted = new ArrayList<>(incomplete.size());
			while (!incomplete.isEmpty()) {
				BulkOperation op = incomplete.get(0);
				BulkResponse single = executeBulk(indexName, Collections.singletonList(op));
				items.addAll(single.items());
				opsSubmitted.add(op);
				incomplete.remove(0);
			}
		}

		BulkItemClassification c = classifyItems(indexName, items, opsSubmitted);
		if (c.hasPermanentFailures()) {
			throw new RuntimeException(buildPermanentFailureMessage(
					attemptFailureSummary(indexName, totalOps, c), c.permanentSamples));
		}
		incomplete.addAll(c.nextRemaining);
		lastRetryable.clear();
		lastRetryable.addAll(c.retryableItems);
		if (incomplete.isEmpty()) {
			return;
		}
		LOG.warn("Bulk index attempt {}/{} for {}: {} retryable of {}; backing off",
				attempt, BULK_INDEX_MAX_RETRIES, indexName, c.retryableItems.size(), opsSubmitted.size());
		throw new RetryException(attemptFailureSummary(indexName, totalOps, c));
	}

	/**
	 * Partitions bulk response items into (ok, retryable, permanent), emits an ERROR log per
	 * permanent failure, and captures up to {@link #MAX_FAILURE_SAMPLES} descriptors for the
	 * exception message. The returned classification is the input to the caller's retry decision.
	 * {@code items} and {@code ops} must be the same length and aligned by index.
	 */
	private BulkItemClassification classifyItems(String indexName, List<BulkResponseItem> items,
			List<BulkOperation> ops) {
		BulkItemClassification c = new BulkItemClassification();
		for (int i = 0; i < items.size(); i++) {
			BulkResponseItem item = items.get(i);
			if (item.error() == null) {
				continue;
			}
			if (isRetryableItemStatus(item.status())) {
				c.retryableItems.add(item);
				c.nextRemaining.add(ops.get(i));
			} else {
				c.permanentFailures++;
				String descriptor = describeBulkItemFailure(item);
				LOG.error("Bulk index item failed in {}: {}", indexName, descriptor);
				if (c.permanentSamples.size() < MAX_FAILURE_SAMPLES) {
					c.permanentSamples.add(descriptor);
				}
			}
		}
		return c;
	}

	private static String attemptFailureSummary(String indexName, int totalOps, BulkItemClassification c) {
		int totalFailures = c.retryableItems.size() + c.permanentFailures;
		return String.format(
				"Bulk index to %s failed: %d document(s) rejected out of %d (%d retryable, %d permanent)",
				indexName, totalFailures, totalOps, c.retryableItems.size(), c.permanentFailures);
	}

	/**
	 * Executes a single bulk request. Envelope-level 429/5xx and IOExceptions are translated to
	 * {@link RetryException} so the outer retry loop backs off; envelope-level 4xx becomes a
	 * permanent {@link RuntimeException}.
	 */
	private BulkResponse executeBulk(String indexName, List<BulkOperation> ops) throws RetryException {
		// Snapshot the list so the BulkRequest isn't mutated when retry state is advanced in place
		// between attempts.
		List<BulkOperation> snapshot = new ArrayList<>(ops);
		try {
			return openSearchClient.bulk(BulkRequest.of(req -> req.operations(snapshot)));
		} catch (OpenSearchException e) {
			String detail = "Failed to bulk index to search index: " + indexName
					+ " (" + describeError(e.error()) + ")";
			// status() == 0 indicates the transport never produced an HTTP response (e.g.
			// the AWS SDK 2 transport surfaced a connection-level failure as
			// OpenSearchException rather than IOException). Treat the same as a 5xx —
			// transient, retryable.
			if (e.status() == 0 || isRetryableItemStatus(e.status())) {
				throw new RetryException(detail, e);
			}
			throw new RuntimeException(detail, e);
		} catch (IOException e) {
			throw new RetryException("Failed to bulk index to search index: " + indexName, e);
		}
	}

	/** Output of {@link #classifyItems} — used by both the batch and per-document paths. */
	private static final class BulkItemClassification {
		final List<BulkOperation> nextRemaining = new ArrayList<>();
		final List<BulkResponseItem> retryableItems = new ArrayList<>();
		final List<String> permanentSamples = new ArrayList<>();
		int permanentFailures;

		boolean hasPermanentFailures() {
			return permanentFailures > 0;
		}
	}

	static boolean isRetryableItemStatus(int status) {
		return status == HTTP_TOO_MANY_REQUESTS
				|| (status >= HTTP_INTERNAL_SERVER_ERROR && status <= HTTP_MAX_SERVER_ERROR);
	}

	/**
	 * Appends up to {@link #MAX_FAILURE_SAMPLES} per-item descriptors to the summary so the
	 * reason reaches the user via SEARCH_INDEX_STATUS.ERROR_MESSAGE (VARCHAR(3000)). The whole
	 * message is hard-capped at {@link #MAX_BULK_ERROR_MESSAGE_CHARS} by substring truncation
	 * to stay safely inside that column width.
	 */
	static String buildPermanentFailureMessage(String summary, List<String> permanentSamples) {
		if (permanentSamples.isEmpty()) {
			return summary;
		}
		StringBuilder sb = new StringBuilder(summary).append(". Sample failures:");
		for (String sample : permanentSamples) {
			sb.append("\n - ").append(sample);
		}
		if (sb.length() > MAX_BULK_ERROR_MESSAGE_CHARS) {
			return sb.substring(0, MAX_BULK_ERROR_MESSAGE_CHARS - TRUNCATION_MARKER.length())
					+ TRUNCATION_MARKER;
		}
		return sb.toString();
	}

	/**
	 * AOSS often returns a generic {@code reason} ("Internal error occurred while processing
	 * request") on the outer error, with the actual cause buried in {@code caused_by},
	 * {@code root_cause[]}, {@code metadata}, or {@code stack_trace}. Surface all of them so
	 * the failure is diagnosable.
	 */
	static String describeError(ErrorCause error) {
		if (error == null) {
			return "?";
		}
		StringBuilder sb = new StringBuilder();
		appendErrorCauseDetail(sb, error);
		ErrorCause current = error.causedBy();
		while (current != null) {
			sb.append(" caused by ");
			appendErrorCauseDetail(sb, current);
			current = current.causedBy();
		}
		return sb.toString();
	}

	private static void appendErrorCauseDetail(StringBuilder sb, ErrorCause c) {
		sb.append(c.type() == null ? "?" : c.type())
				.append(": ")
				.append(c.reason() == null ? "?" : c.reason());
		if (!c.rootCause().isEmpty()) {
			sb.append(" [rootCause=");
			boolean first = true;
			for (ErrorCause rc : c.rootCause()) {
				if (!first) sb.append(", ");
				sb.append(rc.type() == null ? "?" : rc.type())
						.append(": ")
						.append(rc.reason() == null ? "?" : rc.reason());
				first = false;
			}
			sb.append("]");
		}
		if (!c.metadata().isEmpty()) {
			sb.append(" [metadata=").append(c.metadata()).append("]");
		}
		if (c.stackTrace() != null) {
			sb.append(" [stackTrace=").append(c.stackTrace()).append("]");
		}
	}

	/**
	 * Format a single failed {@link BulkResponseItem}.
	 * AOSS often returns a generic {@code error.reason} on each item while leaving the real
	 * cause in {@code shards.failures[]} and {@code status}. Surface both so callers (and the
	 * persisted {@code SEARCH_INDEX_STATUS.errorMessage}) see the whole story.
	 */
	static String describeBulkItemFailure(
			BulkResponseItem item) {
		StringBuilder sb = new StringBuilder();
		sb.append("doc ").append(item.id())
				.append(" [status=").append(item.status()).append("]: ")
				.append(describeError(item.error()));
		ShardStatistics shards = item.shards();
		if (shards != null && !shards.failures().isEmpty()) {
			sb.append(" [shardFailures=");
			boolean first = true;
			for (ShardSearchFailure sf : shards.failures()) {
				if (!first) sb.append(", ");
				sb.append("shard=").append(sf.shard());
				if (sf.index() != null) sb.append(" index=").append(sf.index());
				if (sf.node() != null) sb.append(" node=").append(sf.node());
				sb.append(" reason=").append(describeError(sf.reason()));
				first = false;
			}
			sb.append("]");
		}
		return sb.toString();
	}

	@Override
	public void validateAnalyzerSettings(IndexSettingsAnalysis resolvedSettings) {
		ValidateArgument.required(resolvedSettings, "resolvedSettings");

		// `analyzer.default` must be present — it's the entry every field mapping
		// ultimately binds to (or the entry promoted to the index-wide reserved slot).
		Map<String, Analyzer> analyzers = resolvedSettings.analyzer();
		if (!analyzers.containsKey(DEFAULT_ANALYZER_NAME)) {
			throw new IllegalArgumentException(
					"settings must declare an analyzer named 'default' under analyzer.default.");
		}

		// Validate every analyzer entry, not just `default`. A curator may declare a
		// `default_search` (or any other analyzer) whose chain references a unique filter
		// or tokenizer that doesn't appear in `default`'s chain — validating only the
		// `default` chain would let those errors slip through to async index build time.
		analyzers.forEach((localName, analyzer) -> {
			if (!analyzer.isCustom()) {
				// Built-in analyzers (keyword/standard/etc.) are AOSS-resolved by name and
				// have no chain to validate.
				return;
			}
			CustomAnalyzer custom = analyzer.custom();
			Tokenizer tokenizer = resolveTokenizer(custom.tokenizer(), resolvedSettings.tokenizer());
			List<TokenFilter> tokenFilters = resolveTokenFilters(custom.filter(), resolvedSettings.filter());
			List<CharFilter> charFilters = resolveCharFilters(custom.charFilter(), resolvedSettings.charFilter());
			validateOneAnalyzerEntry(localName, tokenizer, tokenFilters, charFilters);
		});
	}

	/**
	 * Run a single {@code _analyze} round-trip for one analyzer entry's resolved chain.
	 * AOSS occasionally returns a transient {@code index_not_found_exception} from the
	 * cluster-level {@code _analyze} endpoint while a system index is being provisioned;
	 * retry that case (and {@link IOException}) so curators don't see flaky 400s on
	 * legitimate analyzers, and bubble every other OpenSearch error up as a permanent
	 * {@link IllegalArgumentException} naming the offending analyzer entry.
	 */
	private void validateOneAnalyzerEntry(String localName, Tokenizer tokenizer,
			List<TokenFilter> tokenFilters, List<CharFilter> charFilters) {
		try {
			TimeUtils.waitForExponentialMaxRetry(VALIDATE_MAX_RETRIES, VALIDATE_INITIAL_BACKOFF_MS, () -> {
				try {
					openSearchClient.indices().analyze(AnalyzeRequest.of(req -> {
						req.tokenizer(tokenizer);
						req.text("The quick brown fox jumps over the lazy dog");
						if (!tokenFilters.isEmpty()) {
							req.filter(tokenFilters);
						}
						if (!charFilters.isEmpty()) {
							req.charFilter(charFilters);
						}
						return req;
					}));
					return Boolean.TRUE;
				} catch (OpenSearchException e) {
					if (INDEX_NOT_FOUND_EXCEPTION.equals(e.error() != null ? e.error().type() : null)) {
						throw new RetryException(e);
					}
					throw new IllegalArgumentException(
							"Invalid analyzer configuration in '" + localName + "': "
									+ describeError(e.error())
									+ ". Check your tokenizer, token filters, and character filters.", e);
				} catch (IOException e) {
					throw new RetryException(e);
				}
			});
		} catch (RetryException e) {
			throw new IllegalStateException(
					"Unable to validate analyzer settings: the search service is temporarily unavailable. Please try again later.",
					e.getCause());
		} catch (IllegalArgumentException | IllegalStateException e) {
			throw e;
		} catch (Exception e) {
			throw new IllegalStateException(
					"Unable to validate analyzer settings: the search service is temporarily unavailable. Please try again later.", e);
		}
	}

	/**
	 * Resolve a {@code CustomAnalyzer}'s {@code tokenizer} field to a typed {@link Tokenizer}
	 * for the {@code _analyze} request. A name that appears in the local registry is sent
	 * inline as a {@link Tokenizer} {@code definition}; any other name is sent as a
	 * built-in reference. Missing field defaults to {@code "standard"} to match
	 * OpenSearch's analyzer default.
	 */
	private static Tokenizer resolveTokenizer(String tokenizerName, Map<String, Tokenizer> registry) {
		String name = tokenizerName != null ? tokenizerName : "standard";
		Tokenizer registered = registry.get(name);
		return registered != null ? registered : Tokenizer.of(t -> t.name(name));
	}

	/**
	 * Resolve a {@code CustomAnalyzer}'s {@code filter} chain into typed {@link TokenFilter}s.
	 * Each chain element that names a local registry entry is sent inline; everything else
	 * (built-ins like {@code "lowercase"}, plugin-provided filters) goes by name.
	 */
	private static List<TokenFilter> resolveTokenFilters(List<String> chain, Map<String, TokenFilter> registry) {
		if (chain == null || chain.isEmpty()) {
			return Collections.emptyList();
		}
		List<TokenFilter> result = new ArrayList<>(chain.size());
		for (String name : chain) {
			TokenFilter registered = registry.get(name);
			result.add(registered != null ? registered : TokenFilter.of(f -> f.name(name)));
		}
		return result;
	}

	/** Mirror of {@link #resolveTokenFilters} for {@code char_filter}. */
	private static List<CharFilter> resolveCharFilters(List<String> chain, Map<String, CharFilter> registry) {
		if (chain == null || chain.isEmpty()) {
			return Collections.emptyList();
		}
		List<CharFilter> result = new ArrayList<>(chain.size());
		for (String name : chain) {
			CharFilter registered = registry.get(name);
			result.add(registered != null ? registered : CharFilter.of(f -> f.name(name)));
		}
		return result;
	}

	@Override
	public SearchQueryResults search(String indexName, SearchQuery query, List<ColumnModel> columns,
			Set<SearchQueryPart> options) {
		return executeSearch(indexName, query, columns, options);
	}

	@Override
	public SearchQueryResults autocomplete(String indexName, SearchQuery query, List<ColumnModel> columns,
			Set<SearchQueryPart> options) {
		// Autocomplete just clamps the page size — the caller chooses the prefix-style
		// clause inside `query` (typically multi_match with type=bool_prefix or a prefix /
		// match_phrase_prefix clause).
		if (query.getLimit() == null || query.getLimit() > AUTOCOMPLETE_MAX_LIMIT) {
			query.setLimit((long) AUTOCOMPLETE_MAX_LIMIT);
		}
		return executeSearch(indexName, query, columns, options);
	}

	// ---- Private helpers ----

	private SearchQueryResults executeSearch(String indexName, SearchQuery query, List<ColumnModel> columns,
			Set<SearchQueryPart> options) {
		ValidateArgument.requirement(query.getQuery() != null,
				"SearchQuery.query is required (use {\"match_all\":{}} for a catalog-style browse)");

		Map<String, String> nameToId = columns.stream()
				.collect(Collectors.toMap(ColumnModel::getName, ColumnModel::getId, (a2, b) -> a2));
		Map<String, String> idToName = columns.stream()
				.collect(Collectors.toMap(ColumnModel::getId, ColumnModel::getName, (a2, b) -> a2));
		Map<String, ColumnModel> columnMap = columns.stream()
				.collect(Collectors.toMap(ColumnModel::getId, c -> c, (a2, b) -> a2));

		// SearchQuery.offset / .limit are Long; OpenSearch's `from` / `size` are int.
		// Validate up-front instead of letting Long.intValue() silently wrap a value
		// past Integer.MAX_VALUE into a negative int that AOSS interprets unpredictably.
		long offsetLong = query.getOffset() != null ? query.getOffset() : 0L;
		ValidateArgument.requirement(offsetLong >= 0L && offsetLong <= Integer.MAX_VALUE,
				"offset must be between 0 and " + Integer.MAX_VALUE);
		int offset = (int) offsetLong;

		long limitLong = query.getLimit() != null ? query.getLimit() : DEFAULT_LIMIT;
		ValidateArgument.requirement(limitLong >= 0L, "limit must be non-negative");
		int limit = (int) Math.min(limitLong, MAX_LIMIT);

		// Wrap the caller's allowlist-validated query in a server-controlled bool.must so
		// any future server-side filter clauses (e.g. row-level filters) can layer on
		// without re-architecting. SearchIndex content is currently public so no filters
		// are added today; the envelope is still here as a hook.
		Query mainQuery = buildOpaqueQuery(query.getQuery(), nameToId);
		BoolQuery.Builder boolBuilder = new BoolQuery.Builder().must(mainQuery);

		Map<String, Aggregation> aggregations = query.getAggregations() != null
				? buildOpaqueAggregations(query.getAggregations(), nameToId)
				: Collections.emptyMap();

		org.opensearch.client.opensearch.core.search.Suggester suggester = query.getSuggest() != null
				? buildOpaqueSuggest(query.getSuggest(), nameToId)
				: null;

		List<String> returnFields = resolveReturnFieldsAsIds(query.getReturnFields(), nameToId);

		List<SortOptions> sortOptions = options.contains(SearchQueryPart.HITS)
				? buildSortOptions(query.getSort(), columnMap, nameToId)
				: Collections.emptyList();

		List<FieldValue> searchAfter = parseSearchAfter(query.getSearchAfter());

		return callSearchApi(indexName, boolBuilder, offset, limit, aggregations,
				suggester, returnFields, sortOptions, idToName, options, searchAfter);
	}

	@SuppressWarnings("rawtypes")
	SearchQueryResults callSearchApi(String indexName, BoolQuery.Builder boolBuilder,
			int offset, int limit, Map<String, Aggregation> aggregations,
			org.opensearch.client.opensearch.core.search.Suggester suggester,
			List<String> returnFields, List<SortOptions> sortOptions,
			Map<String, String> idToName, Set<SearchQueryPart> options,
			List<FieldValue> searchAfter) {
		boolean returnHits = options.contains(SearchQueryPart.HITS);
		boolean returnTotalHits = options.contains(SearchQueryPart.TOTAL_HITS);
		boolean usingCursor = searchAfter != null && !searchAfter.isEmpty();
		try {
			SearchResponse<Map> response = openSearchClient.search(req -> {
				req.index(indexName);
				req.query(q -> q.bool(boolBuilder.build()));
				// `from` and `searchAfter` are mutually exclusive; pin from=0 when a cursor is
				// supplied so AOSS uses the cursor for pagination.
				req.from(usingCursor ? 0 : offset);
				// size=0 when hits aren't requested — saves source fetch + transport cost
				req.size(returnHits ? limit : 0);
				if (returnTotalHits) {
					// Use a count value instead of enabled(true) — the boolean form caps at 10k
					req.trackTotalHits(t -> t.count(Integer.MAX_VALUE));
				} else {
					req.trackTotalHits(t -> t.enabled(false));
				}

				if (!aggregations.isEmpty()) {
					req.aggregations(aggregations);
				}
				if (suggester != null) {
					// Suggesters return their own response section, independent of hits.
					req.suggest(suggester);
				}
				// Source filters and sort are meaningless without hits.
				if (returnHits) {
					if (returnFields != null && !returnFields.isEmpty()) {
						req.source(src -> src.filter(f -> f.includes(returnFields)));
					}
					if (!sortOptions.isEmpty()) {
						req.sort(sortOptions);
					}
					if (usingCursor) {
						req.searchAfter(searchAfter);
					}
				}

				return req;
			}, Map.class);

			return convertResponse(response, indexName, offset, idToName, options);
		} catch (OpenSearchException e) {
			if (INDEX_NOT_FOUND_EXCEPTION.equals(e.error().type())) {
				throw new IllegalStateException("Search index is still building. Please try again later.", e);
			}
			throw new RuntimeException("Failed to execute search on search index: " + indexName
					+ " (" + describeError(e.error()) + ")", e);
		} catch (IOException e) {
			throw new RuntimeException("Failed to execute search on search index: " + indexName, e);
		}
	}

	/**
	 * Convert a caller-supplied opaque OpenSearch query DSL into a typed {@link Query}.
	 * Steps: parse the JSON shape into a {@link JsonNode} via SearchOpaqueJsonUtil, run
	 * the allowlist (rejecting scripts / cross-index reach / depth-cap exceeded with
	 * HTTP 400), rewrite every column-name field reference to its column id, then
	 * deserialize the cleared subtree through OpenSearch's typed {@code Query} deserializer.
	 */
	Query buildOpaqueQuery(Object opaqueQueryDsl, Map<String, String> nameToId) {
		JsonNode dsl = SearchOpaqueJsonUtil.parse(opaqueQueryDsl);
		SearchQueryDslAllowlist.validate(dsl);
		SearchFieldRewriter.rewriteQuery(dsl, name -> nameToId.getOrDefault(name, name));
		return SearchOpaqueJsonUtil.fromJsonpTree(dsl, Query._DESERIALIZER);
	}

	/**
	 * Convert a caller-supplied opaque OpenSearch aggregations object into a typed
	 * map of aggregation name to typed {@link Aggregation}, after allowlist validation
	 * and column-name → column-id rewriting.
	 */
	Map<String, Aggregation> buildOpaqueAggregations(Object opaqueAggsDsl, Map<String, String> nameToId) {
		JsonNode dsl = SearchOpaqueJsonUtil.parse(opaqueAggsDsl);
		SearchAggregationDslAllowlist.validate(dsl);
		SearchFieldRewriter.rewriteAggregations(dsl, name -> nameToId.getOrDefault(name, name));
		Map<String, Aggregation> result = new HashMap<>();
		Iterator<Map.Entry<String, JsonNode>> entries = dsl.fields();
		while (entries.hasNext()) {
			Map.Entry<String, JsonNode> entry = entries.next();
			result.put(entry.getKey(),
					SearchOpaqueJsonUtil.fromJsonpTree(entry.getValue(), Aggregation._DESERIALIZER));
		}
		return result;
	}

	/**
	 * Convert a caller-supplied opaque suggesters object into a typed
	 * {@link org.opensearch.client.opensearch.core.search.Suggester}, after allowlist
	 * validation and column-name → column-id rewriting.
	 */
	org.opensearch.client.opensearch.core.search.Suggester buildOpaqueSuggest(Object opaqueSuggestDsl,
			Map<String, String> nameToId) {
		JsonNode dsl = SearchOpaqueJsonUtil.parse(opaqueSuggestDsl);
		SearchSuggestDslAllowlist.validate(dsl);
		SearchFieldRewriter.rewriteSuggest(dsl, name -> nameToId.getOrDefault(name, name));
		return SearchOpaqueJsonUtil.fromJsonpTree(dsl,
				org.opensearch.client.opensearch.core.search.Suggester._DESERIALIZER);
	}

	/**
	 * Translate a list of caller-supplied user-facing column names to their column ids
	 * for the OpenSearch {@code _source.includes} filter. Unknown names pass through
	 * unchanged (the existing relaxed-name behavior).
	 */
	private static List<String> resolveReturnFieldsAsIds(List<String> returnFields,
			Map<String, String> nameToId) {
		if (returnFields == null || returnFields.isEmpty()) {
			return null;
		}
		List<String> resolved = new ArrayList<>(returnFields.size());
		for (String name : returnFields) {
			resolved.add(nameToId.getOrDefault(name, name));
		}
		return resolved;
	}

	/**
	 * Parse an opaque {@code searchAfter} cursor (a list of JSON-typed sort values, the
	 * same shape we emit on {@code nextSearchAfter}) into the typed
	 * {@link FieldValue} list OpenSearch expects. Returns {@code null} when no cursor was
	 * supplied so the caller can branch on the cursor presence.
	 */
	private static List<FieldValue> parseSearchAfter(List<Object> searchAfter) {
		if (searchAfter == null || searchAfter.isEmpty()) {
			return null;
		}
		List<FieldValue> values = new ArrayList<>(searchAfter.size());
		for (Object element : searchAfter) {
			JsonNode node = SearchOpaqueJsonUtil.parse(element);
			values.add(SearchOpaqueJsonUtil.fromJsonpTree(node, FieldValue._DESERIALIZER));
		}
		return values;
	}

	List<SortOptions> buildSortOptions(List<SortField> sortFields,
			Map<String, ColumnModel> columnMap, Map<String, String> nameToId) {
		if (sortFields == null || sortFields.isEmpty()) {
			return Collections.singletonList(
				SortOptions.of(so -> so.field(FieldSort.of(fs -> fs.field("_score").order(SortOrder.Desc))))
			);
		}
		return sortFields.stream()
				.map(sf -> {
					String sortField;
					if ("_score".equals(sf.getColumnName())) {
						sortField = "_score";
					} else {
						String columnId = nameToId.getOrDefault(sf.getColumnName(), sf.getColumnName());
						sortField = getFilterFieldName(columnId, columnMap);
					}
					SortOrder order = (sf.getDirection() == SortDirection.ASC) ? SortOrder.Asc : SortOrder.Desc;
					return SortOptions.of(so -> so.field(FieldSort.of(fs -> fs.field(sortField).order(order))));
				})
				.collect(Collectors.toList());
	}

	String getFilterFieldName(String columnId, Map<String, ColumnModel> columnMap) {
		ColumnModel column = columnMap.get(columnId);
		if (column == null) {
			return columnId;
		}

		ColumnType colType = column.getColumnType();

		// TEXT and LINK both map to text fields with a `.keyword` sub-field for exact match.
		if (ColumnTypeToOpenSearchMapping.isTextType(colType)
				|| ColumnTypeToOpenSearchMapping.isLinkType(colType)) {
			return columnId + "." + SUB_FIELD_KEYWORD;
		}

		return columnId;
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	SearchQueryResults convertResponse(SearchResponse<Map> response, String indexName, int offset,
			Map<String, String> idToName, Set<SearchQueryPart> options) {
		SearchQueryResults results = new SearchQueryResults();
		results.setOffset((long) offset);

		if (options.contains(SearchQueryPart.TOTAL_HITS)) {
			results.setTotalHits(response.hits().total() != null ? response.hits().total().value() : 0L);
		}

		List<Hit<Map>> hits = response.hits().hits();
		if (options.contains(SearchQueryPart.HITS)) {
			List<SearchHit> converted = new ArrayList<>();
			for (Hit<Map> hit : hits) {
				converted.add(convertHit(hit, idToName));
			}
			results.setHits(converted);
		}

		// Aggregations: serialize the raw response block to JSON, rewrite column-id field
		// references back to column names, and surface as the opaque aggregationResults
		// string. Always populate when the response carried aggregations (the request
		// already gated whether to ask for them).
		if (response.aggregations() != null && !response.aggregations().isEmpty()) {
			results.setAggregationResults(SearchOpaqueJsonUtil.serializeAggregations(
					response.aggregations(), id -> idToName.getOrDefault(id, id)));
		}

		// Suggest: same shape, opaque JSON string with column ids rewritten back.
		if (response.suggest() != null && !response.suggest().isEmpty()) {
			results.setSuggestResults(SearchOpaqueJsonUtil.serializeSuggest(
					response.suggest(), id -> idToName.getOrDefault(id, id)));
		}

		// Pagination cursor: when hits are requested and the page is full, emit the last
		// hit's sort values as the next-page cursor; null when the page is short or empty.
		if (options.contains(SearchQueryPart.HITS) && hits != null && !hits.isEmpty()) {
			List<FieldValue> sortValues = hits.get(hits.size() - 1).sort();
			if (sortValues != null && !sortValues.isEmpty()) {
				results.setNextSearchAfter(SearchOpaqueJsonUtil.toSearchAfterCursor(sortValues));
			}
		}

		return results;
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	SearchHit convertHit(Hit<Map> hit, Map<String, String> idToName) {
		SearchHit searchHit = new SearchHit();
		searchHit.setScore(hit.score());

		Map<String, Object> source = hit.source();
		if (source != null) {
			searchHit.setRowId(toLong(source.get(SYSTEM_FIELD_ROW_ID)));
			searchHit.setRowVersion(toLong(source.get(SYSTEM_FIELD_ROW_VERSION)));

			List<SearchFieldValue> fields = source.entrySet().stream()
					.filter(e -> !SYSTEM_FIELD_ROW_ID.equals(e.getKey()) && !SYSTEM_FIELD_ROW_VERSION.equals(e.getKey()))
					.map(e -> {
						SearchFieldValue fv = new SearchFieldValue();
						fv.setName(idToName.getOrDefault(e.getKey(), e.getKey()));
						fv.setValue(convertFieldValue(e.getValue()));
						return fv;
					})
					.collect(Collectors.toList());
			searchHit.setFields(fields);
		}

		return searchHit;
	}

	/**
	 * Stringify a value from an AOSS hit's {@code _source} for {@link SearchFieldValue#setValue(String)}.
	 * Lists and maps (i.e. {@code *_LIST} and {@code JSON} columns) are written as canonical JSON
	 * so clients can parse them back; scalars use {@link String#valueOf(Object)} so a raw {@code String}
	 * column is not double-quoted in the response. Mirrors the pattern at {@code SQLUtils#bindListColumns}
	 * (lib-table-cluster) which serializes typed Java lists for the table index DB the same way.
	 */
	static String convertFieldValue(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof Collection || value instanceof Map) {
			return SearchOpaqueJsonUtil.writeValueAsString(value, "Failed to serialize search field value");
		}
		return String.valueOf(value);
	}

	/**
	 * Build the OpenSearch field property for a given column type and resolved analyzer qnames.
	 *
	 * <p>Package-private for branch-coverage tests across all column types.</p>
	 */
	Property buildProperty(ColumnType columnType,
			String qname, boolean hasDefaultSearch) {

		// LINK columns map exactly like TEXT columns. Users who want full-text search
		// on a URL pick a text-style analyzer via ColumnAnalyzerOverride; users who want
		// exact-match-only stay on the KEYWORD analyzer and rely on the `.keyword` sub-field.
		if (ColumnTypeToOpenSearchMapping.isTextType(columnType)
				|| ColumnTypeToOpenSearchMapping.isLinkType(columnType)) {
			return buildTextProperty(columnType, qname, hasDefaultSearch);
		}

		if (ColumnTypeToOpenSearchMapping.isKeywordType(columnType)) {
			Integer ignoreAbove = ColumnTypeToOpenSearchMapping.getIgnoreAbove(columnType);
			int ia = ignoreAbove != null ? ignoreAbove : 256;
			return Property.of(p -> p.keyword(k -> k.ignoreAbove(ia)));
		}

		if (ColumnTypeToOpenSearchMapping.isLongType(columnType)) {
			return Property.of(p -> p.long_(l -> l));
		}

		if (ColumnTypeToOpenSearchMapping.isDoubleType(columnType)) {
			return Property.of(p -> p.double_(d -> d));
		}

		if (ColumnTypeToOpenSearchMapping.isBooleanType(columnType)) {
			return Property.of(p -> p.boolean_(b -> b));
		}

		if (ColumnTypeToOpenSearchMapping.isJsonType(columnType)) {
			return Property.of(p -> p.object(o -> o.dynamic(DynamicMapping.True)));
		}

		// Fallback: text
		return Property.of(p -> p.text(t -> t));
	}

	private Property buildTextProperty(ColumnType columnType, String qname, boolean hasDefaultSearch) {
		Integer ignoreAbove = ColumnTypeToOpenSearchMapping.getIgnoreAbove(columnType);
		final int finalIa = ignoreAbove != null ? ignoreAbove : 1000;
		// When qname is null OpenSearch falls through to the index-wide
		// analysis.analyzer.default (and default_search). For a field bound to a specific
		// TextAnalyzer we must bind both the index- and search-time analyzer explicitly so
		// the index-wide `default_search` (registered for the SearchConfiguration's PRIMARY
		// TextAnalyzer) does not hijack a non-primary field at query time. Search-time
		// precedence (per the OpenSearch docs) is:
		//   1. query analyzer  >  2. field search_analyzer  >  3. analysis.analyzer.default_search
		//   >  4. field analyzer  >  5. standard
		// Without a per-field search_analyzer (rule 2), rule 3 wins for any non-primary field
		// — the wrong analyzer. Setting search_analyzer explicitly forces rule 2 to win:
		//   - asymmetric TextAnalyzer (declares its own default_search)  →  bind to that
		//     entry's namespaced registry key.
		//   - symmetric TextAnalyzer (no default_search)  →  bind to the same namespaced
		//     `default` registry key as the index analyzer.
		final String indexKey = toAossKey(qname);
		final String searchKey;
		if (qname == null) {
			searchKey = null;
		} else if (hasDefaultSearch) {
			searchKey = toAossKey(qname) + "__" + DEFAULT_SEARCH_ANALYZER_NAME;
		} else {
			searchKey = indexKey;
		}

		return Property.of(p -> p.text(t -> {
			if (indexKey != null) {
				t.analyzer(indexKey);
			}
			if (searchKey != null) {
				t.searchAnalyzer(searchKey);
			}
			t.fields(SUB_FIELD_KEYWORD, f -> f.keyword(k -> k.ignoreAbove(finalIa)));
			return t;
		}));
	}

	Map<String, ColumnAnalyzerOverrideEntry> buildOverrideMap(
			List<ColumnAnalyzerOverride> columnAnalyzerOverrides, Map<String, String> nameToId) {
		Map<String, ColumnAnalyzerOverrideEntry> map = new HashMap<>();
		if (columnAnalyzerOverrides == null) {
			return map;
		}
		for (ColumnAnalyzerOverride cao : columnAnalyzerOverrides) {
			if (cao.getOverrides() == null) {
				continue;
			}
			for (ColumnAnalyzerOverrideEntry entry : cao.getOverrides()) {
				String columnId = nameToId.get(entry.getColumnName());
				if (columnId != null) {
					map.putIfAbsent(columnId, entry);
				}
			}
		}
		return map;
	}

	Long toLong(Object value) {
		if (value instanceof Number) {
			return ((Number) value).longValue();
		}
		try {
			return Long.parseLong(String.valueOf(value));
		} catch (NumberFormatException e) {
			return null;
		}
	}

}
