package org.sagebionetworks.repo.manager.search;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.stream.JsonParser;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.json.JsonpMapper;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch._types.analysis.CharFilter;
import org.opensearch.client.opensearch._types.analysis.CharFilterDefinition;
import org.opensearch.client.opensearch._types.analysis.TokenFilter;
import org.opensearch.client.opensearch._types.analysis.TokenFilterDefinition;
import org.opensearch.client.opensearch._types.analysis.Tokenizer;
import org.opensearch.client.opensearch._types.analysis.TokenizerDefinition;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldSort;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch._types.SortOptions;
import org.opensearch.client.opensearch._types.SortOrder;
import org.opensearch.client.opensearch._types.aggregations.Aggregate;
import org.opensearch.client.opensearch._types.aggregations.Aggregation;
import org.opensearch.client.opensearch._types.aggregations.StringTermsBucket;
import org.opensearch.client.opensearch._types.mapping.DynamicMapping;
import org.opensearch.client.opensearch._types.mapping.Property;
import org.opensearch.client.opensearch._types.query_dsl.BoolQuery;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch._types.query_dsl.TextQueryType;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.opensearch.client.opensearch.core.search.HighlightField;
import org.opensearch.client.opensearch.core.search.Hit;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.opensearch.client.opensearch.indices.CreateIndexResponse;
import org.opensearch.client.opensearch.indices.IndexSettingsAnalysis;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.repo.model.table.FacetColumnResult;
import org.sagebionetworks.repo.model.table.FacetColumnResultValueCount;
import org.sagebionetworks.repo.model.table.FacetColumnResultValues;
import org.sagebionetworks.repo.model.table.FacetType;
import org.sagebionetworks.repo.model.search.table.ColumnAnalyzerOverride;
import org.sagebionetworks.repo.model.search.table.ColumnAnalyzerOverrideEntry;
import org.sagebionetworks.repo.model.search.FacetRequest;
import org.sagebionetworks.repo.model.search.FacetSortField;
import org.sagebionetworks.repo.model.search.SearchFieldValue;
import org.sagebionetworks.repo.model.search.SearchHit;
import org.sagebionetworks.repo.model.search.SearchQuery;
import org.sagebionetworks.repo.model.search.SearchQueryType;
import org.sagebionetworks.repo.model.search.SearchQueryResults;
import org.sagebionetworks.repo.model.search.SortDirection;
import org.sagebionetworks.repo.model.search.SortField;
import org.sagebionetworks.repo.model.search.table.SynonymRule;
import org.sagebionetworks.repo.model.search.table.SynonymRuleType;
import org.sagebionetworks.repo.model.search.table.SynonymSet;
import org.sagebionetworks.repo.model.search.table.TextAnalyzer;
import org.sagebionetworks.repo.model.search.table.TextAnalyzerSettings;
import org.sagebionetworks.repo.model.search.KeyRange;
import org.sagebionetworks.repo.model.search.KeyValues;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Implementation of {@link OpenSearchManager} that wraps the OpenSearch Java client
 * for all AOSS operations.
 */
@Service
public class OpenSearchManagerImpl implements OpenSearchManager {

	private static final String SYSTEM_FIELD_ROW_ID = "_row_id";
	private static final String SYSTEM_FIELD_ROW_VERSION = "_row_version";
	private static final String SUB_FIELD_KEYWORD = "keyword";
	private static final String SUB_FIELD_SEARCHABLE = "searchable";
	private static final String INDEX_NOT_FOUND_EXCEPTION = "index_not_found_exception";
	private static final String ANALYZER_PREFIX = "synapse_analyzer_";
	private static final String SYNONYM_FILTER_NAME = "synapse_synonyms";

	private static final int DEFAULT_LIMIT = 25;
	private static final int MAX_LIMIT = 100;
	private static final int AUTOCOMPLETE_MAX_LIMIT = 8;
	private static final int DEFAULT_FACET_SIZE = 25;

	private static final Logger LOG = LogManager.getLogger(OpenSearchManagerImpl.class);

	private final OpenSearchClient openSearchClient;

	public OpenSearchManagerImpl(OpenSearchClient openSearchClient) {
		this.openSearchClient = openSearchClient;
	}

	@Override
	public String createIndex(String indexName, List<ColumnModel> columns, String defaultAnalyzer,
			List<SynonymSet> synonymSets, List<ColumnAnalyzerOverride> columnAnalyzerOverrides,
			Map<String, TextAnalyzer> analyzers) {

		List<String> synonymRules = buildSynonymRules(synonymSets);
		boolean hasSynonyms = !synonymRules.isEmpty();

		Map<String, String> nameToId = columns.stream()
				.collect(Collectors.toMap(ColumnModel::getName, ColumnModel::getId, (a2, b) -> a2));
		Map<String, ColumnAnalyzerOverrideEntry> overrideMap = buildOverrideMap(columnAnalyzerOverrides, nameToId);

		try {
			CreateIndexRequest request = CreateIndexRequest.of(req -> req
					.index(indexName)
					.settings(s -> s.analysis(a -> {
						buildAnalysisSettings(a, analyzers, hasSynonyms, synonymRules);
						return a;
					}))
					.mappings(m -> {
						buildMappings(m, columns, defaultAnalyzer, overrideMap, analyzers);
						return m;
					})
			);

			String appliedConfigJson = request.toJsonString();
			CreateIndexResponse response = openSearchClient.indices().create(request);

			if (Boolean.TRUE.equals(response.acknowledged())) {
				LOG.info("Search index {} created successfully.", indexName);
			} else {
				LOG.error("Search index {} creation was not acknowledged.", indexName);
			}

			return appliedConfigJson;
		} catch (OpenSearchException e) {
			if ("resource_already_exists_exception".equals(e.error().type())) {
				LOG.warn("Search index {} already exists.", indexName);
				return null;
			}
			throw new RuntimeException("Failed to create search index: " + indexName, e);
		} catch (IOException e) {
			throw new RuntimeException("Failed to create search index: " + indexName, e);
		}
	}

	private void buildAnalysisSettings(IndexSettingsAnalysis.Builder a,
			Map<String, TextAnalyzer> analyzers, boolean hasSynonyms, List<String> synonymRules) {
		if (hasSynonyms) {
			a.filter(SYNONYM_FILTER_NAME, f -> f.definition(d -> d
					.synonym(syn -> syn.synonyms(synonymRules))));
		}
		for (Map.Entry<String, TextAnalyzer> entry : analyzers.entrySet()) {
			registerAnalyzer(a, entry.getValue(), hasSynonyms);
		}
	}

	private void registerAnalyzer(IndexSettingsAnalysis.Builder a, TextAnalyzer analyzer, boolean hasSynonyms) {
		TextAnalyzerSettings settings = analyzer.getSettings();

		if (settings.getTokenFilters() != null) {
			registerTokenFilters(a, settings.getTokenFilters());
		}
		if (settings.getCharFilters() != null) {
			registerCharFilters(a, settings.getCharFilters());
		}

		String tokenizer = settings.getTokenizer() != null ? settings.getTokenizer() : "standard";
		if (settings.getTokenizerConfig() != null && !settings.getTokenizerConfig().isEmpty()) {
			String customTokenizerName = "synapse_tokenizer_" + analyzer.getId();
			registerTokenizer(a, customTokenizerName, settings.getTokenizerConfig());
			tokenizer = customTokenizerName;
		}

		List<String> filters = new ArrayList<>();
		if (settings.getFilterOrder() != null) {
			filters.addAll(settings.getFilterOrder());
		}
		if (Boolean.TRUE.equals(settings.getSynonymAware()) && hasSynonyms) {
			filters.add(SYNONYM_FILTER_NAME);
		}

		String analyzerName = ANALYZER_PREFIX + analyzer.getId();
		final String finalTokenizer = tokenizer;
		final List<String> finalFilters = filters;

		a.analyzer(analyzerName, an -> an.custom(c -> {
			c.tokenizer(finalTokenizer);
			if (!finalFilters.isEmpty()) {
				c.filter(finalFilters);
			}
			if (settings.getCharFilterOrder() != null && !settings.getCharFilterOrder().isEmpty()) {
				c.charFilter(settings.getCharFilterOrder());
			}
			return c;
		}));
	}

	private void buildMappings(org.opensearch.client.opensearch._types.mapping.TypeMapping.Builder m,
			List<ColumnModel> columns, String defaultAnalyzer,
			Map<String, ColumnAnalyzerOverrideEntry> overrideMap, Map<String, TextAnalyzer> analyzers) {
		m.properties(SYSTEM_FIELD_ROW_ID, p -> p.long_(l -> l));
		m.properties(SYSTEM_FIELD_ROW_VERSION, p -> p.long_(l -> l));

		Map<Long, String> idToQualifiedName = buildIdToQualifiedNameMap(analyzers);

		for (ColumnModel column : columns) {
			String columnId = column.getId();
			ColumnType columnType = column.getColumnType();
			String effectiveAnalyzerName = resolveEffectiveAnalyzerName(
					columnId, columnType, defaultAnalyzer, overrideMap, idToQualifiedName);
			TextAnalyzer effectiveAnalyzer = analyzers.get(effectiveAnalyzerName);
			ValidateArgument.required(effectiveAnalyzer, "analyzer '" + effectiveAnalyzerName + "' for column " + columnId);
			ColumnAnalyzerOverrideEntry entry = overrideMap.get(columnId);

			m.properties(columnId, buildProperty(columnType, effectiveAnalyzer, entry, analyzers));
		}
	}

	@Override
	public void deleteIndex(String indexName) {
		try {
			openSearchClient.indices().delete(req -> req.index(indexName));
			LOG.info("Search index {} deleted.", indexName);
		} catch (OpenSearchException e) {
			if (!INDEX_NOT_FOUND_EXCEPTION.equals(e.error().type())) {
				throw new RuntimeException("Failed to delete search index: " + indexName, e);
			}
			LOG.info("Search index {} does not exist; delete is a no-op.", indexName);
		} catch (IOException e) {
			throw new RuntimeException("Failed to delete search index: " + indexName, e);
		}
	}

	@Override
	public long bulkIndex(String indexName, List<Map<String, Object>> documents) {

		List<BulkOperation> operations = documents.stream()
				.map(doc -> {
					Object rowId = doc.get(SYSTEM_FIELD_ROW_ID);
					ValidateArgument.required(rowId, SYSTEM_FIELD_ROW_ID);
					String docId = String.valueOf(rowId);
					return BulkOperation.of(op -> op
							.index(idx -> idx
									.index(indexName)
									.id(docId)
									.document(doc)));
				})
				.collect(Collectors.toList());

		if (operations.isEmpty()) {
			return 0L;
		}

		try {
			BulkResponse response = openSearchClient.bulk(req -> req.operations(operations));

			long errorCount = response.items().stream()
					.filter(item -> item.error() != null)
					.peek(item -> LOG.error(
							"Bulk index error for doc {} in {}: {} (type: {})",
							item.id(), indexName, item.error().reason(), item.error().type()))
					.count();

			if (errorCount > 0) {
				throw new RuntimeException(String.format(
						"Bulk index to %s failed: %d document(s) rejected out of %d.",
						indexName, errorCount, documents.size()));
			}

			return (long) response.items().size();
		} catch (OpenSearchException | IOException e) {
			throw new RuntimeException("Failed to bulk index to search index: " + indexName, e);
		}
	}

	@Override
	public SearchQueryResults search(String indexName, SearchQuery query, List<ColumnModel> columns,
			String defaultAnalyzer, List<ColumnAnalyzerOverride> columnAnalyzerOverrides,
			Map<String, TextAnalyzer> analyzers) {
		return executeSearch(indexName, query, columns, defaultAnalyzer, columnAnalyzerOverrides, analyzers);
	}

	@Override
	public SearchQueryResults autocomplete(String indexName, SearchQuery query, List<ColumnModel> columns,
			String defaultAnalyzer, List<ColumnAnalyzerOverride> columnAnalyzerOverrides,
			Map<String, TextAnalyzer> analyzers) {
		query.setQueryType(SearchQueryType.PREFIX);
		if (query.getLimit() == null || query.getLimit() > AUTOCOMPLETE_MAX_LIMIT) {
			query.setLimit((long) AUTOCOMPLETE_MAX_LIMIT);
		}
		return executeSearch(indexName, query, columns, defaultAnalyzer, columnAnalyzerOverrides, analyzers);
	}

	// ---- Private helpers ----

	/**
	 * Register all custom token filters from a JSON string of the form:
	 * {"filterName": {"type": "...", ...}, "filterName2": {"type": "...", ...}}
	 * Each key-value pair is deserialized into a typed {@link TokenFilterDefinition}.
	 */
	private void registerTokenFilters(IndexSettingsAnalysis.Builder a, String filtersJson) {
		JsonpMapper mapper = new JacksonJsonpMapper();
		try (JsonReader reader = Json.createReader(new StringReader(filtersJson))) {
			JsonObject obj = reader.readObject();
			for (String filterName : obj.keySet()) {
				String valueJson = obj.getJsonObject(filterName).toString();
				try (JsonParser parser = Json.createParser(new StringReader(valueJson))) {
					TokenFilterDefinition def = TokenFilterDefinition._DESERIALIZER.deserialize(parser, mapper);
					a.filter(filterName, f -> f.definition(def));
				}
			}
		}
	}

	/**
	 * Register all custom character filters from a JSON string of the form:
	 * {"filterName": {"type": "...", ...}, "filterName2": {"type": "...", ...}}
	 */
	private void registerCharFilters(IndexSettingsAnalysis.Builder a, String filtersJson) {
		JsonpMapper mapper = new JacksonJsonpMapper();
		try (JsonReader reader = Json.createReader(new StringReader(filtersJson))) {
			JsonObject obj = reader.readObject();
			for (String filterName : obj.keySet()) {
				String valueJson = obj.getJsonObject(filterName).toString();
				try (JsonParser parser = Json.createParser(new StringReader(valueJson))) {
					CharFilterDefinition def = CharFilterDefinition._DESERIALIZER.deserialize(parser, mapper);
					a.charFilter(filterName, f -> f.definition(def));
				}
			}
		}
	}

	/**
	 * Register a custom tokenizer by deserializing the JSON config.
	 */
	private void registerTokenizer(IndexSettingsAnalysis.Builder a, String tokenizerName, String tokenizerConfigJson) {
		JsonpMapper mapper = new JacksonJsonpMapper();
		try (JsonParser parser = Json.createParser(new StringReader(tokenizerConfigJson))) {
			TokenizerDefinition def = TokenizerDefinition._DESERIALIZER.deserialize(parser, mapper);
			a.tokenizer(tokenizerName, t -> t.definition(def));
		}
	}

	private SearchQueryResults executeSearch(String indexName, SearchQuery query, List<ColumnModel> columns,
			String defaultAnalyzer, List<ColumnAnalyzerOverride> columnAnalyzerOverrides,
			Map<String, TextAnalyzer> analyzers) {

		Map<String, String> nameToId = columns.stream()
				.collect(Collectors.toMap(ColumnModel::getName, ColumnModel::getId, (a2, b) -> a2));
		Map<String, ColumnAnalyzerOverrideEntry> overrideMap = buildOverrideMap(columnAnalyzerOverrides, nameToId);
		Map<String, ColumnModel> columnMap = columns.stream()
				.collect(Collectors.toMap(ColumnModel::getId, c -> c, (a2, b) -> a2));
		Map<Long, String> idToQualifiedName = buildIdToQualifiedNameMap(analyzers);

		SearchQueryType queryType = query.getQueryType() != null ? query.getQueryType()
				: SearchQueryType.SIMPLE_QUERY_STRING;
		String queryText = query.getQueryText();
		int offset = query.getOffset() != null ? query.getOffset().intValue() : 0;
		int limit = query.getLimit() != null ? Math.min(query.getLimit().intValue(), MAX_LIMIT) : DEFAULT_LIMIT;
		String fuzziness = query.getFuzziness();

		if (queryText == null || queryText.trim().isEmpty()) {
			queryType = SearchQueryType.MATCH_ALL;
		}

		final SearchQueryType finalQueryType = queryType;
		final String finalQueryText = queryText;

		List<String> resolvedQueryFields = resolveQueryFields(query.getQueryFields(), columns, defaultAnalyzer, overrideMap, analyzers, idToQualifiedName, true);

		LOG.info("OpenSearch query on {}: type={}, text='{}', resolvedFields={}, offset={}, limit={}, fuzziness={}",
				indexName, finalQueryType, finalQueryText, resolvedQueryFields, offset, limit, fuzziness);

		BoolQuery.Builder boolBuilder = new BoolQuery.Builder();

		Query mainQuery = buildMainQuery(finalQueryType, finalQueryText, resolvedQueryFields, fuzziness);
		boolBuilder.must(mainQuery);

		addFilters(boolBuilder, query, columnMap, nameToId, defaultAnalyzer, overrideMap, analyzers, idToQualifiedName);

		Map<String, Aggregation> aggregations = buildAggregations(query.getFacetRequests(), columnMap, nameToId, defaultAnalyzer,
				overrideMap, analyzers, idToQualifiedName);

		Map<String, HighlightField> highlightFields = null;
		if (Boolean.TRUE.equals(query.getHighlight())) {
			highlightFields = buildHighlightFields(columns, defaultAnalyzer, overrideMap, analyzers, idToQualifiedName);
		}

		List<String> returnFields = query.getReturnFields();

		List<SortOptions> sortOptions = buildSortOptions(query.getSort(), columnMap, nameToId, defaultAnalyzer, overrideMap, analyzers, idToQualifiedName);

		if (query.getTermsFilters() != null
				|| query.getRangeFilters() != null || query.getExistsFilters() != null
				|| query.getNotExistsFilters() != null) {
			LOG.info("OpenSearch query on {} includes filters: terms={}, range={}, exists={}, notExists={}",
					indexName,
					query.getTermsFilters() != null ? query.getTermsFilters().size() : 0,
					query.getRangeFilters() != null ? query.getRangeFilters().size() : 0,
					query.getExistsFilters() != null ? query.getExistsFilters().size() : 0,
					query.getNotExistsFilters() != null ? query.getNotExistsFilters().size() : 0);
		}

		if (!aggregations.isEmpty()) {
			LOG.info("OpenSearch query on {} includes {} facet aggregations: {}", indexName, aggregations.size(), aggregations.keySet());
		}

		Map<String, String> idToName = columns.stream()
				.collect(Collectors.toMap(ColumnModel::getId, ColumnModel::getName, (a2, b) -> a2));

		return callSearchApi(indexName, boolBuilder, offset, limit, aggregations,
				highlightFields, returnFields, sortOptions, idToName);
	}

	@SuppressWarnings("rawtypes")
	private SearchQueryResults callSearchApi(String indexName, BoolQuery.Builder boolBuilder,
			int offset, int limit, Map<String, Aggregation> aggregations,
			Map<String, HighlightField> highlightFields, List<String> returnFields,
			List<SortOptions> sortOptions, Map<String, String> idToName) {
		try {
			SearchResponse<Map> response = openSearchClient.search(req -> {
				req.index(indexName);
				req.query(q -> q.bool(boolBuilder.build()));
				req.from(offset);
				req.size(limit);

				if (!aggregations.isEmpty()) {
					req.aggregations(aggregations);
				}
				if (highlightFields != null && !highlightFields.isEmpty()) {
					req.highlight(h -> h.fields(highlightFields));
				}
				if (returnFields != null && !returnFields.isEmpty()) {
					req.source(src -> src.filter(f -> f.includes(returnFields)));
				}
				if (!sortOptions.isEmpty()) {
					req.sort(sortOptions);
				}

				return req;
			}, Map.class);

			long totalHits = response.hits().total() != null ? response.hits().total().value() : 0L;
			int returnedHits = response.hits().hits().size();
			LOG.info("OpenSearch response from {}: totalHits={}, returnedHits={}, took={}ms",
					indexName, totalHits, returnedHits, response.took());

			return convertResponse(response, indexName, offset, idToName);
		} catch (OpenSearchException e) {
			if (INDEX_NOT_FOUND_EXCEPTION.equals(e.error().type())) {
				LOG.warn("OpenSearch index {} not found yet (AOSS eventual consistency), treating as still building.", indexName);
				throw new IllegalStateException("Search index is still building. Please try again later.", e);
			}
			LOG.error("OpenSearch query failed on {}: {}", indexName, e.getMessage());
			throw new RuntimeException("Failed to execute search on search index: " + indexName, e);
		} catch (IOException e) {
			LOG.error("OpenSearch query failed on {}: {}", indexName, e.getMessage());
			throw new RuntimeException("Failed to execute search on search index: " + indexName, e);
		}
	}

	private Query buildMainQuery(SearchQueryType queryType, String queryText, List<String> fields, String fuzziness) {
		switch (queryType) {
			case SIMPLE_QUERY_STRING:
				return Query.of(q -> q.simpleQueryString(sqs -> {
					sqs.query(queryText);
					if (fields != null && !fields.isEmpty()) {
						sqs.fields(fields);
					}
					return sqs;
				}));
			case MATCH:
				ValidateArgument.requiredNotEmpty(fields, "fields for MATCH query");
				String matchField = stripBoost(fields.get(0));
				return Query.of(q -> q.match(m -> {
					m.field(matchField).query(FieldValue.of(queryText));
					if (fuzziness != null) {
						m.fuzziness(fuzziness);
					}
					return m;
				}));
			case MULTI_MATCH:
				return Query.of(q -> q.multiMatch(mm -> {
					mm.query(queryText);
					if (fields != null && !fields.isEmpty()) {
						mm.fields(fields);
					}
					if (fuzziness != null) {
						mm.fuzziness(fuzziness);
					}
					return mm;
				}));
			case MATCH_PHRASE:
				ValidateArgument.requiredNotEmpty(fields, "fields for MATCH_PHRASE query");
				String phraseField = stripBoost(fields.get(0));
				return Query.of(q -> q.matchPhrase(mp -> mp.field(phraseField).query(queryText)));
			case PREFIX:
				return Query.of(q -> q.multiMatch(mm -> {
					mm.query(queryText);
					mm.type(TextQueryType.BoolPrefix);
					if (fields != null && !fields.isEmpty()) {
						mm.fields(fields);
					}
					return mm;
				}));
			case WILDCARD:
				ValidateArgument.requiredNotEmpty(fields, "fields for WILDCARD query");
				String wildcardField = stripBoost(fields.get(0));
				return Query.of(q -> q.wildcard(w -> w.field(wildcardField).value(queryText)));
			case MATCH_ALL:
			default:
				return Query.of(q -> q.matchAll(m -> m));
		}
	}

	private void addFilters(BoolQuery.Builder boolBuilder, SearchQuery query,
			Map<String, ColumnModel> columnMap, Map<String, String> nameToId, String defaultAnalyzer,
			Map<String, ColumnAnalyzerOverrideEntry> overrideMap, Map<String, TextAnalyzer> analyzers,
			Map<Long, String> idToQualifiedName) {
		addTermsFilters(boolBuilder, query.getTermsFilters(), columnMap, nameToId, defaultAnalyzer, overrideMap, analyzers, idToQualifiedName);
		addRangeFilters(boolBuilder, query.getRangeFilters(), columnMap, nameToId, defaultAnalyzer, overrideMap, analyzers, idToQualifiedName);
		addExistsFilters(boolBuilder, query.getExistsFilters(), nameToId, false);
		addExistsFilters(boolBuilder, query.getNotExistsFilters(), nameToId, true);
	}

	private void addTermsFilters(BoolQuery.Builder boolBuilder, List<KeyValues> termsFilters,
			Map<String, ColumnModel> columnMap, Map<String, String> nameToId, String defaultAnalyzer,
			Map<String, ColumnAnalyzerOverrideEntry> overrideMap, Map<String, TextAnalyzer> analyzers,
			Map<Long, String> idToQualifiedName) {
		if (termsFilters == null) {
			return;
		}
		for (KeyValues kvs : termsFilters) {
			String columnId = nameToId.getOrDefault(kvs.getKey(), kvs.getKey());
			String fieldName = getFilterFieldName(columnId, columnMap, defaultAnalyzer, overrideMap, analyzers, idToQualifiedName);
			List<FieldValue> fieldValues = kvs.getValues().stream()
					.map(FieldValue::of)
					.collect(Collectors.toList());
			Query termsQuery = Query.of(q -> q.terms(t -> t
					.field(fieldName)
					.terms(tv -> tv.value(fieldValues))));
			if (Boolean.TRUE.equals(kvs.getNot())) {
				boolBuilder.mustNot(termsQuery);
			} else {
				boolBuilder.filter(termsQuery);
			}
		}
	}

	private void addRangeFilters(BoolQuery.Builder boolBuilder, List<KeyRange> rangeFilters,
			Map<String, ColumnModel> columnMap, Map<String, String> nameToId, String defaultAnalyzer,
			Map<String, ColumnAnalyzerOverrideEntry> overrideMap, Map<String, TextAnalyzer> analyzers,
			Map<Long, String> idToQualifiedName) {
		if (rangeFilters == null) {
			return;
		}
		for (KeyRange kr : rangeFilters) {
			String columnId = nameToId.getOrDefault(kr.getKey(), kr.getKey());
			String fieldName = getFilterFieldName(columnId, columnMap, defaultAnalyzer, overrideMap, analyzers, idToQualifiedName);
			Query rangeQuery = Query.of(q -> q.range(r -> {
				r.field(fieldName);
				if (kr.getMin() != null) {
					r.gte(JsonData.of(kr.getMin()));
				}
				if (kr.getMax() != null) {
					r.lte(JsonData.of(kr.getMax()));
				}
				return r;
			}));
			boolBuilder.filter(rangeQuery);
		}
	}

	private void addExistsFilters(BoolQuery.Builder boolBuilder, List<String> fields,
			Map<String, String> nameToId, boolean negate) {
		if (fields == null) {
			return;
		}
		for (String fieldName : fields) {
			String fieldId = nameToId.getOrDefault(fieldName, fieldName);
			Query existsQuery = Query.of(q -> q.exists(e -> e.field(fieldId)));
			if (negate) {
				boolBuilder.mustNot(existsQuery);
			} else {
				boolBuilder.filter(existsQuery);
			}
		}
	}

	private Map<String, Aggregation> buildAggregations(List<FacetRequest> facetRequests,
			Map<String, ColumnModel> columnMap, Map<String, String> nameToId, String defaultAnalyzer,
			Map<String, ColumnAnalyzerOverrideEntry> overrideMap, Map<String, TextAnalyzer> analyzers,
			Map<Long, String> idToQualifiedName) {
		if (facetRequests == null || facetRequests.isEmpty()) {
			return Collections.emptyMap();
		}

		Map<String, Aggregation> aggregations = new HashMap<>();
		for (FacetRequest facet : facetRequests) {
			String columnId = nameToId.getOrDefault(facet.getColumnName(), facet.getColumnName());
			String fieldName = getFilterFieldName(columnId, columnMap, defaultAnalyzer, overrideMap, analyzers, idToQualifiedName);
			int maxValues = facet.getMaxValueCount() != null ? facet.getMaxValueCount().intValue() : DEFAULT_FACET_SIZE;

			String sortField = facet.getSortField() == FacetSortField.KEY ? "_key" : "_count";
			SortOrder sortOrder = (facet.getSortDirection() != null && facet.getSortDirection() == SortDirection.ASC)
					? SortOrder.Asc : SortOrder.Desc;
			final String finalSortField = sortField;
			final SortOrder finalSortOrder = sortOrder;

			aggregations.put(columnId, Aggregation.of(a -> a
					.terms(t -> t.field(fieldName).size(maxValues)
							.order(List.of(Map.of(finalSortField, finalSortOrder))))));
		}
		return aggregations;
	}

	private Map<String, HighlightField> buildHighlightFields(List<ColumnModel> columns,
			String defaultAnalyzer, Map<String, ColumnAnalyzerOverrideEntry> overrideMap,
			Map<String, TextAnalyzer> analyzers, Map<Long, String> idToQualifiedName) {
		Map<String, HighlightField> highlightFields = new HashMap<>();
		for (ColumnModel column : columns) {
			String columnId = column.getId();
			ColumnType colType = column.getColumnType();
			if (!ColumnTypeToOpenSearchMapping.isTextType(colType) && !ColumnTypeToOpenSearchMapping.isLinkType(colType)) {
				continue;
			}
			String effectiveName = resolveEffectiveAnalyzerName(
					columnId, colType, defaultAnalyzer, overrideMap, idToQualifiedName);
			TextAnalyzer analyzer = analyzers.get(effectiveName);
			if (ColumnTypeToOpenSearchMapping.isLinkType(colType) && isKeywordAnalyzer(analyzer)) {
				highlightFields.put(columnId + "." + SUB_FIELD_SEARCHABLE, HighlightField.of(h -> h));
			} else {
				highlightFields.put(columnId, HighlightField.of(h -> h));
			}
		}
		return highlightFields;
	}

	private List<SortOptions> buildSortOptions(List<SortField> sortFields,
			Map<String, ColumnModel> columnMap, Map<String, String> nameToId, String defaultAnalyzer,
			Map<String, ColumnAnalyzerOverrideEntry> overrideMap, Map<String, TextAnalyzer> analyzers,
			Map<Long, String> idToQualifiedName) {
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
						sortField = getFilterFieldName(columnId, columnMap, defaultAnalyzer, overrideMap, analyzers, idToQualifiedName);
					}
					SortOrder order = (sf.getDirection() == SortDirection.ASC) ? SortOrder.Asc : SortOrder.Desc;
					return SortOptions.of(so -> so.field(FieldSort.of(fs -> fs.field(sortField).order(order))));
				})
				.collect(Collectors.toList());
	}

	private String getFilterFieldName(String columnId, Map<String, ColumnModel> columnMap,
			String defaultAnalyzer, Map<String, ColumnAnalyzerOverrideEntry> overrideMap,
			Map<String, TextAnalyzer> analyzers, Map<Long, String> idToQualifiedName) {
		ColumnModel column = columnMap.get(columnId);
		if (column == null) {
			return columnId;
		}

		ColumnType colType = column.getColumnType();

		if (ColumnTypeToOpenSearchMapping.isTextType(colType)) {
			return columnId + "." + SUB_FIELD_KEYWORD;
		}

		if (ColumnTypeToOpenSearchMapping.isLinkType(colType)) {
			String effectiveName = resolveEffectiveAnalyzerName(columnId, colType, defaultAnalyzer, overrideMap, idToQualifiedName);
			TextAnalyzer analyzer = analyzers != null ? analyzers.get(effectiveName) : null;
			if (isKeywordAnalyzer(analyzer)) {
				return columnId;
			}
			return columnId + "." + SUB_FIELD_KEYWORD;
		}

		return columnId;
	}

	private String getSearchFieldName(String columnId, Map<String, ColumnModel> columnMap,
			String defaultAnalyzer, Map<String, ColumnAnalyzerOverrideEntry> overrideMap,
			Map<String, TextAnalyzer> analyzers, Map<Long, String> idToQualifiedName) {
		ColumnModel column = columnMap.get(columnId);
		if (column == null) {
			return columnId;
		}

		ColumnType colType = column.getColumnType();

		if (ColumnTypeToOpenSearchMapping.isLinkType(colType)) {
			String effectiveName = resolveEffectiveAnalyzerName(columnId, colType, defaultAnalyzer, overrideMap, idToQualifiedName);
			TextAnalyzer analyzer = analyzers != null ? analyzers.get(effectiveName) : null;
			if (isKeywordAnalyzer(analyzer)) {
				return columnId + "." + SUB_FIELD_SEARCHABLE;
			}
		}

		return columnId;
	}

	private List<String> resolveQueryFields(List<String> queryFields, List<ColumnModel> columns,
			String defaultAnalyzer, Map<String, ColumnAnalyzerOverrideEntry> overrideMap,
			Map<String, TextAnalyzer> analyzers, Map<Long, String> idToQualifiedName, boolean forSearch) {
		if (queryFields == null || queryFields.isEmpty()) {
			return null;
		}

		Map<String, ColumnModel> columnMap = columns.stream()
				.collect(Collectors.toMap(ColumnModel::getId, c -> c, (a2, b) -> a2));
		Map<String, String> nameToIdLocal = columns.stream()
				.collect(Collectors.toMap(ColumnModel::getName, ColumnModel::getId, (a2, b) -> a2));

		return queryFields.stream()
				.map(field -> {
					String boost = null;
					String fieldName = field;
					int caretIndex = field.indexOf('^');
					if (caretIndex > 0) {
						fieldName = field.substring(0, caretIndex);
						boost = field.substring(caretIndex);
					}
					String columnId = nameToIdLocal.getOrDefault(fieldName, fieldName);
					String resolved = forSearch
							? getSearchFieldName(columnId, columnMap, defaultAnalyzer, overrideMap, analyzers, idToQualifiedName)
							: getFilterFieldName(columnId, columnMap, defaultAnalyzer, overrideMap, analyzers, idToQualifiedName);
					return boost != null ? resolved + boost : resolved;
				})
				.collect(Collectors.toList());
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private SearchQueryResults convertResponse(SearchResponse<Map> response, String indexName, int offset,
			Map<String, String> idToName) {
		SearchQueryResults results = new SearchQueryResults();
		results.setTotalHits(response.hits().total() != null ? response.hits().total().value() : 0L);
		results.setOffset((long) offset);

		List<SearchHit> hits = new ArrayList<>();
		for (Hit<Map> hit : response.hits().hits()) {
			hits.add(convertHit(hit, idToName));
		}
		results.setHits(hits);

		if (response.aggregations() != null && !response.aggregations().isEmpty()) {
			results.setFacets(convertAggregations(response.aggregations(), idToName));
		}

		return results;
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private SearchHit convertHit(Hit<Map> hit, Map<String, String> idToName) {
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
						fv.setValue(e.getValue() != null ? String.valueOf(e.getValue()) : null);
						return fv;
					})
					.collect(Collectors.toList());
			searchHit.setFields(fields);
		}

		if (hit.highlight() != null && !hit.highlight().isEmpty()) {
			searchHit.setHighlights(convertHighlights(hit.highlight(), idToName));
		}

		return searchHit;
	}

	private List<SearchFieldValue> convertHighlights(Map<String, List<String>> highlightMap,
			Map<String, String> idToName) {
		List<SearchFieldValue> highlights = new ArrayList<>();
		for (Map.Entry<String, List<String>> entry : highlightMap.entrySet()) {
			String fieldName = entry.getKey();
			if (fieldName.endsWith("." + SUB_FIELD_SEARCHABLE)) {
				fieldName = fieldName.substring(0, fieldName.length() - SUB_FIELD_SEARCHABLE.length() - 1);
			}
			fieldName = idToName.getOrDefault(fieldName, fieldName);
			SearchFieldValue hv = new SearchFieldValue();
			hv.setName(fieldName);
			hv.setValue(String.join(" ... ", entry.getValue()));
			highlights.add(hv);
		}
		return highlights;
	}

	private List<FacetColumnResult> convertAggregations(Map<String, Aggregate> aggregations,
			Map<String, String> idToName) {
		List<FacetColumnResult> facets = new ArrayList<>();
		for (Map.Entry<String, Aggregate> entry : aggregations.entrySet()) {
			String columnName = idToName.getOrDefault(entry.getKey(), entry.getKey());
			Aggregate aggregate = entry.getValue();

			if (aggregate.isSterms()) {
				List<FacetColumnResultValueCount> valueCounts = aggregate.sterms().buckets().array().stream()
						.map(bucket -> buildFacetValueCount(bucket.key(), bucket.docCount()))
						.collect(Collectors.toList());
				facets.add(buildFacetResult(columnName, valueCounts));
			} else if (aggregate.isLterms()) {
				List<FacetColumnResultValueCount> valueCounts = aggregate.lterms().buckets().array().stream()
						.map(bucket -> buildFacetValueCount(bucket.keyAsString(), bucket.docCount()))
						.collect(Collectors.toList());
				facets.add(buildFacetResult(columnName, valueCounts));
			} else if (aggregate.isDterms()) {
				List<FacetColumnResultValueCount> valueCounts = aggregate.dterms().buckets().array().stream()
						.map(bucket -> buildFacetValueCount(String.valueOf(bucket.key()), bucket.docCount()))
						.collect(Collectors.toList());
				facets.add(buildFacetResult(columnName, valueCounts));
			}
		}
		return facets;
	}

	private FacetColumnResultValues buildFacetResult(String columnName, List<FacetColumnResultValueCount> valueCounts) {
		FacetColumnResultValues result = new FacetColumnResultValues();
		result.setColumnName(columnName);
		result.setFacetType(FacetType.enumeration);
		result.setFacetValues(valueCounts);
		return result;
	}

	private FacetColumnResultValueCount buildFacetValueCount(String key, long docCount) {
		FacetColumnResultValueCount vc = new FacetColumnResultValueCount();
		vc.setValue(key);
		vc.setCount(docCount);
		vc.setIsSelected(false);
		return vc;
	}

	/**
	 * Build the OpenSearch field property for a given column type and effective analyzer.
	 */
	private Property buildProperty(ColumnType columnType,
			TextAnalyzer effectiveAnalyzer, ColumnAnalyzerOverrideEntry entry,
			Map<String, TextAnalyzer> analyzers) {

		if (ColumnTypeToOpenSearchMapping.isTextType(columnType)) {
			return buildTextProperty(columnType, effectiveAnalyzer, entry, analyzers);
		}

		if (ColumnTypeToOpenSearchMapping.isLinkType(columnType)) {
			if (isKeywordAnalyzer(effectiveAnalyzer)) {
				return buildKeywordWithSearchableProperty(analyzers);
			}
			return buildTextProperty(columnType, effectiveAnalyzer, entry, analyzers);
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

	private Property buildTextProperty(ColumnType columnType,
			TextAnalyzer effectiveAnalyzer, ColumnAnalyzerOverrideEntry entry,
			Map<String, TextAnalyzer> analyzers) {
		Integer ignoreAbove = ColumnTypeToOpenSearchMapping.getIgnoreAbove(columnType);
		int ia = ignoreAbove != null ? ignoreAbove : 1000;

		String indexAnalyzerName = resolveIndexAnalyzerName(effectiveAnalyzer, entry, analyzers);
		String searchAnalyzerName = resolveSearchAnalyzerName(indexAnalyzerName, entry, analyzers);
		final int finalIa = ia;

		return Property.of(p -> p.text(t -> {
			t.analyzer(indexAnalyzerName);
			if (!indexAnalyzerName.equals(searchAnalyzerName)) {
				t.searchAnalyzer(searchAnalyzerName);
			}
			t.fields(SUB_FIELD_KEYWORD, f -> f.keyword(k -> k.ignoreAbove(finalIa)));
			return t;
		}));
	}

	private String resolveIndexAnalyzerName(TextAnalyzer effectiveAnalyzer,
			ColumnAnalyzerOverrideEntry entry, Map<String, TextAnalyzer> analyzers) {
		if (entry != null && entry.getIndexAnalyzer() != null) {
			return analyzerToOpenSearchName(analyzers.get(entry.getIndexAnalyzer()));
		}
		return analyzerToOpenSearchName(effectiveAnalyzer);
	}

	private String resolveSearchAnalyzerName(String indexAnalyzerName,
			ColumnAnalyzerOverrideEntry entry, Map<String, TextAnalyzer> analyzers) {
		if (entry != null && entry.getSearchAnalyzer() != null) {
			return analyzerToOpenSearchName(analyzers.get(entry.getSearchAnalyzer()));
		}
		return indexAnalyzerName;
	}

	private Property buildKeywordWithSearchableProperty(Map<String, TextAnalyzer> analyzers) {
		Map<Long, String> reverse = buildIdToQualifiedNameMap(analyzers);
		String scientificQualifiedName = reverse.get(TextAnalyzerBootstrapper.SCIENTIFIC_ID);
		TextAnalyzer scientificAnalyzer = analyzers.get(scientificQualifiedName);
		String scientificName = analyzerToOpenSearchName(scientificAnalyzer);
		return Property.of(p -> p.keyword(k -> k
				.ignoreAbove(1000)
				.fields(SUB_FIELD_SEARCHABLE, f -> f.text(t -> t
						.analyzer(scientificName)))));
	}

	/**
	 * Resolve the effective analyzer qualified name for a column, checking overrides first,
	 * then the default analyzer, then the column type default.
	 */
	String resolveEffectiveAnalyzerName(String columnId, ColumnType columnType,
			String defaultAnalyzer, Map<String, ColumnAnalyzerOverrideEntry> overrideMap,
			Map<Long, String> idToQualifiedName) {
		ColumnAnalyzerOverrideEntry entry = overrideMap.get(columnId);
		if (entry != null && entry.getIndexAnalyzer() != null) {
			return entry.getIndexAnalyzer();
		}
		if (defaultAnalyzer != null) {
			return defaultAnalyzer;
		}
		Long defaultId = ColumnTypeToOpenSearchMapping.getDefaultAnalyzerId(columnType);
		return idToQualifiedName.get(defaultId);
	}

	private Map<String, ColumnAnalyzerOverrideEntry> buildOverrideMap(
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

	private List<String> buildSynonymRules(List<SynonymSet> synonymSets) {
		if (synonymSets == null || synonymSets.isEmpty()) {
			return Collections.emptyList();
		}
		List<String> rules = new ArrayList<>();
		for (SynonymSet ss : synonymSets) {
			if (ss.getRules() == null) {
				continue;
			}
			for (SynonymRule rule : ss.getRules()) {
				if (rule.getTerms() == null || rule.getTerms().size() < 2) {
					continue;
				}
				if (rule.getRuleType() == SynonymRuleType.EQUIVALENT) {
					rules.add(String.join(", ", rule.getTerms()));
				} else if (rule.getRuleType() == SynonymRuleType.EXPLICIT) {
					String source = rule.getTerms().get(0);
					List<String> targets = rule.getTerms().subList(1, rule.getTerms().size());
					rules.add(source + " => " + String.join(", ", targets));
				}
			}
		}
		return rules;
	}

	private String analyzerToOpenSearchName(TextAnalyzer analyzer) {
		return ANALYZER_PREFIX + analyzer.getId();
	}

	static Map<Long, String> buildIdToQualifiedNameMap(Map<String, TextAnalyzer> analyzers) {
		Map<Long, String> result = new HashMap<>();
		for (Map.Entry<String, TextAnalyzer> entry : analyzers.entrySet()) {
			result.put(Long.parseLong(entry.getValue().getId()), entry.getKey());
		}
		return result;
	}

	private boolean isKeywordAnalyzer(TextAnalyzer analyzer) {
		return analyzer != null && analyzer.getSettings() != null
				&& "keyword".equals(analyzer.getSettings().getTokenizer());
	}

	private String stripBoost(String fieldSpec) {
		int caretIndex = fieldSpec.indexOf('^');
		return caretIndex > 0 ? fieldSpec.substring(0, caretIndex) : fieldSpec;
	}

	private Long toLong(Object value) {
		if (value instanceof Number) {
			return ((Number) value).longValue();
		}
		try {
			return Long.parseLong(String.valueOf(value));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	@Override
	public void validateAnalyzerSettings(TextAnalyzerSettings settings) {
		ValidateArgument.required(settings, "settings");

		Tokenizer tokenizer = buildTokenizer(settings);
		List<TokenFilter> tokenFilters = buildTokenFilters(settings);
		List<CharFilter> charFilters = buildCharFilters(settings);

		try {
			openSearchClient.indices().analyze(req -> {
				req.tokenizer(tokenizer);
				req.text("The quick brown fox jumps over the lazy dog");
				if (!tokenFilters.isEmpty()) {
					req.filter(tokenFilters);
				}
				if (!charFilters.isEmpty()) {
					req.charFilter(charFilters);
				}
				return req;
			});
		} catch (OpenSearchException e) {
			LOG.debug("AOSS _analyze validation failed: {}", e.getMessage());
			LOG.warn("TextAnalyzer validation failed: {}", e.error().reason());
			throw new IllegalArgumentException(
				"Invalid analyzer configuration: " + e.error().reason()
				+ ". Check your tokenizer, token filters, and character filters.", e);
		} catch (IOException e) {
			LOG.warn("Failed to reach AOSS for analyzer validation", e);
			throw new IllegalStateException(
				"Unable to validate analyzer settings: the search service is temporarily unavailable. Please try again later.", e);
		}
	}

	private Tokenizer buildTokenizer(TextAnalyzerSettings settings) {
		try {
			if (settings.getTokenizerConfig() != null && !settings.getTokenizerConfig().isEmpty()) {
				return Tokenizer.of(t -> t.definition(parseTokenizerDefinition(settings.getTokenizerConfig())));
			}
			String tokenizerName = settings.getTokenizer() != null ? settings.getTokenizer() : "standard";
			return Tokenizer.of(t -> t.name(tokenizerName));
		} catch (Exception e) {
			throw new IllegalArgumentException("Invalid tokenizer configuration: " + e.getMessage(), e);
		}
	}

	private List<TokenFilter> buildTokenFilters(TextAnalyzerSettings settings) {
		Map<String, TokenFilterDefinition> tokenFilterDefs = Collections.emptyMap();
		try {
			if (settings.getTokenFilters() != null && !settings.getTokenFilters().isEmpty()) {
				tokenFilterDefs = parseTokenFilterDefinitions(settings.getTokenFilters());
			}
		} catch (Exception e) {
			throw new IllegalArgumentException("Invalid tokenFilters JSON: " + e.getMessage(), e);
		}

		List<TokenFilter> tokenFilters = new ArrayList<>();
		if (settings.getFilterOrder() == null) {
			return tokenFilters;
		}
		for (String filterName : settings.getFilterOrder()) {
			TokenFilterDefinition def = tokenFilterDefs.get(filterName);
			if (def != null) {
				tokenFilters.add(TokenFilter.of(f -> f.definition(def)));
			} else {
				tokenFilters.add(TokenFilter.of(f -> f.name(filterName)));
			}
		}
		return tokenFilters;
	}

	private List<CharFilter> buildCharFilters(TextAnalyzerSettings settings) {
		Map<String, CharFilterDefinition> charFilterDefs = Collections.emptyMap();
		try {
			if (settings.getCharFilters() != null && !settings.getCharFilters().isEmpty()) {
				charFilterDefs = parseCharFilterDefinitions(settings.getCharFilters());
			}
		} catch (Exception e) {
			throw new IllegalArgumentException("Invalid charFilters JSON: " + e.getMessage(), e);
		}

		List<CharFilter> charFilters = new ArrayList<>();
		if (settings.getCharFilterOrder() == null) {
			return charFilters;
		}
		for (String filterName : settings.getCharFilterOrder()) {
			CharFilterDefinition def = charFilterDefs.get(filterName);
			if (def != null) {
				charFilters.add(CharFilter.of(f -> f.definition(def)));
			} else {
				charFilters.add(CharFilter.of(f -> f.name(filterName)));
			}
		}
		return charFilters;
	}

	private Map<String, TokenFilterDefinition> parseTokenFilterDefinitions(String filtersJson) {
		Map<String, TokenFilterDefinition> result = new HashMap<>();
		JsonpMapper mapper = new JacksonJsonpMapper();
		try (JsonReader reader = Json.createReader(new StringReader(filtersJson))) {
			JsonObject obj = reader.readObject();
			for (String filterName : obj.keySet()) {
				String valueJson = obj.getJsonObject(filterName).toString();
				try (JsonParser parser = Json.createParser(new StringReader(valueJson))) {
					TokenFilterDefinition def = TokenFilterDefinition._DESERIALIZER.deserialize(parser, mapper);
					result.put(filterName, def);
				}
			}
		}
		return result;
	}

	private Map<String, CharFilterDefinition> parseCharFilterDefinitions(String filtersJson) {
		Map<String, CharFilterDefinition> result = new HashMap<>();
		JsonpMapper mapper = new JacksonJsonpMapper();
		try (JsonReader reader = Json.createReader(new StringReader(filtersJson))) {
			JsonObject obj = reader.readObject();
			for (String filterName : obj.keySet()) {
				String valueJson = obj.getJsonObject(filterName).toString();
				try (JsonParser parser = Json.createParser(new StringReader(valueJson))) {
					CharFilterDefinition def = CharFilterDefinition._DESERIALIZER.deserialize(parser, mapper);
					result.put(filterName, def);
				}
			}
		}
		return result;
	}

	private TokenizerDefinition parseTokenizerDefinition(String tokenizerConfigJson) {
		JsonpMapper mapper = new JacksonJsonpMapper();
		try (JsonParser parser = Json.createParser(new StringReader(tokenizerConfigJson))) {
			return TokenizerDefinition._DESERIALIZER.deserialize(parser, mapper);
		}
	}
}
