package org.sagebionetworks.repo.manager.search.oss;


import org.apache.commons.lang3.StringUtils;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.SortOrder;
import org.opensearch.client.opensearch._types.SuggestMode;
import org.opensearch.client.opensearch._types.aggregations.Aggregate;
import org.opensearch.client.opensearch._types.aggregations.Aggregation;
import org.opensearch.client.opensearch._types.aggregations.FiltersAggregation;
import org.opensearch.client.opensearch._types.aggregations.FiltersBucket;
import org.opensearch.client.opensearch._types.aggregations.LongTermsAggregate;
import org.opensearch.client.opensearch._types.aggregations.LongTermsBucket;
import org.opensearch.client.opensearch._types.aggregations.StringTermsAggregate;
import org.opensearch.client.opensearch._types.aggregations.StringTermsBucket;
import org.opensearch.client.opensearch._types.query_dsl.BoolQuery;
import org.opensearch.client.opensearch._types.query_dsl.MatchAllQuery;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch._types.query_dsl.RangeQuery;
import org.opensearch.client.opensearch._types.query_dsl.TermQuery;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.opensearch.client.opensearch.core.search.Suggest;
import org.opensearch.client.opensearch.core.search.Suggester;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.search.DocumentFields;
import org.sagebionetworks.repo.model.search.Facet;
import org.sagebionetworks.repo.model.search.FacetConstraint;
import org.sagebionetworks.repo.model.search.FacetTypeNames;
import org.sagebionetworks.repo.model.search.SearchResults;
import org.sagebionetworks.repo.model.search.query.KeyRange;
import org.sagebionetworks.repo.model.search.query.KeyValue;
import org.sagebionetworks.repo.model.search.query.Option;
import org.sagebionetworks.repo.model.search.query.SearchFacetOption;
import org.sagebionetworks.repo.model.search.query.SearchFacetSort;
import org.sagebionetworks.repo.model.search.query.SearchQuery;
import org.sagebionetworks.repo.model.search.query.Suggestion;
import org.sagebionetworks.repo.model.search.query.SuggestionQuery;
import org.sagebionetworks.repo.model.search.query.SuggestionResults;
import org.sagebionetworks.repo.manager.search.SearchConstants;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.sagebionetworks.repo.manager.search.SearchConstants.FIELD_ACL;


public class OssUtil {
    public final static String NAME_FIELD = "name";
    public final static String DESCRIPTION_FIELD = "description";
    public final static String TERM_NAME_SUGGESTION = "term_name_suggestion";
    public final static String TERM_DESCRIPTION_SUGGESTION = "term_description_suggestion";

    public static SearchRequest generateSearchRequestForSuggestion(SuggestionQuery suggestionQuery) {
        ValidateArgument.required(suggestionQuery, "suggestionQuery");
        ValidateArgument.requiredNotEmpty(suggestionQuery.getSearchTerm(), "suggestionQuery.searchTerm");
        List<String> terms = suggestionQuery.getSearchTerm();
        List<String> quotedList = new ArrayList<>();
        List<String> unquotedList = new ArrayList<>();
        int size = suggestionQuery.getSize() != null ? Math.toIntExact(suggestionQuery.getSize()) : 5;
        int maxEdits = suggestionQuery.getMaxEdit() != null ? Math.toIntExact(suggestionQuery.getMaxEdit()) : 2;

        SearchRequest.Builder searchBuilder = new SearchRequest.Builder()
                .index(SearchConstants.OPEN_SEARCH_INDEX_NAME).size(0);
        Suggester.Builder suggesterBuilder = new Suggester.Builder();

        terms.forEach(term -> {
            term = term.trim();
            int length = term.length();
            if (length > 2 && term.startsWith("\"") && term.endsWith("\"")) {
                // Safe removal of quotes
                quotedList.add(term.substring(1, length - 1));
            } else {
                unquotedList.add(term);
            }
        });

        addTermSuggester(suggesterBuilder, size, maxEdits);
        suggesterBuilder.text(String.join(" ", unquotedList));

        Suggester suggester = suggesterBuilder.build();
        return searchBuilder.suggest(suggester).build();
    }

    public static void addTermSuggester(Suggester.Builder suggesterBuilder, int size, int maxEdits) {
        suggesterBuilder.suggesters(TERM_NAME_SUGGESTION, s -> s.term(
                ts -> ts
                        .field(NAME_FIELD)
                        .size(size)
                        .maxEdits(maxEdits)
                        .suggestMode(SuggestMode.Missing)
        ));
        suggesterBuilder.suggesters(TERM_DESCRIPTION_SUGGESTION, s -> s.term(
                ts -> ts
                        .field(DESCRIPTION_FIELD)
                        .size(size)
                        .maxEdits(maxEdits)
                        .suggestMode(SuggestMode.Missing
                        )));
    }

    public static SuggestionResults convertToSynapseSuggestionResult(Map<String, List<Suggest<DocumentFields>>> suggestions) {
        ValidateArgument.required(suggestions, "suggestions");
        SuggestionResults results = new SuggestionResults();
        Map<String, Set<Option>> map1 = new HashMap<>();

        suggestions.entrySet().stream()
                .map(Map.Entry::getValue)
                .filter(suggestList -> !suggestList.isEmpty())
                .flatMap(List::stream)
                .filter(suggest -> suggest != null && suggest.term() != null)
                .forEach(suggest -> {
                    Set<Option> optionSet = map1.computeIfAbsent(suggest.term().text(), k -> new HashSet<>());
                    suggest.term().options().stream()
                            .filter(option -> option != null && StringUtils.isNotEmpty(option.text()))
                            .map(option -> new Option()
                                    .setTerm(option.text())
                                    .setScore(option.score()))
                            .forEach(optionSet::add);
                });


        List<Suggestion> suggestionList = map1.entrySet()
                .stream()
                .map(entry -> { // 2. Transform each entry into a Suggestion object
                    return new Suggestion()
                            .setKey(entry.getKey())
                            .setValues(entry.getValue());
                })
                .collect(Collectors.toList());

        return results.setSuggestions(suggestionList);

    }

    public static SuggestionResults eliminateSuggestionWithAccessDenied(SuggestionResults suggestionResults, Map<String, Aggregate> aggregateResponse) {
        ValidateArgument.required(suggestionResults, "suggestionResults");
        ValidateArgument.required(aggregateResponse, "aggregateResponse");

        Aggregate aggregate = aggregateResponse.get("query_counts");
        Map<String, FiltersBucket> filteredBucketMap = aggregate.filters().buckets().keyed();
        for (Suggestion suggestion : suggestionResults.getSuggestions()) {

            // Stream over the existing options (suggestion.getValues()),
            // filter them, and collect the valid ones.
            if (suggestion.getValues() == null || suggestion.getValues().isEmpty()) {
                continue;
            }
            List<Option> filteredOptions = suggestion.getValues().stream()
                    .filter(option -> {
                        String term = option.getTerm();

                        // Check if the term exists in the map AND its docCount is > 0.
                        return term != null && filteredBucketMap.containsKey(term) &&
                                filteredBucketMap.get(term).docCount() > 0;
                    })
                    .map(option -> {
                        String term = option.getTerm();
                        option.setFrequency(filteredBucketMap.get(term).docCount());
                        return option;
                    })
                    .collect(Collectors.toList());
            suggestion.setValues(new HashSet<>(filteredOptions));
        }
        return suggestionResults;
    }

    public static SearchRequest generateAggregationRequestToLimitAccess(UserInfo userInfo, SuggestionResults suggestionResults) {
        SearchRequest.Builder searchBuilder = new SearchRequest.Builder()
                .index(SearchConstants.OPEN_SEARCH_INDEX_NAME);
        BoolQuery.Builder boolBuilder = new BoolQuery.Builder();
        Set<Long> userGroups = getAuthorizedUserGroups(userInfo);
        Map<String, Query> aggregations = new HashMap<>();
        if (!CollectionUtils.isEmpty(userGroups)) {
            boolBuilder.filter(
                    Query.of(tq -> tq.terms(t -> t
                            .field(FIELD_ACL)
                            .terms(queryTerm -> queryTerm.value(
                                    userGroups.stream()
                                            .map(FieldValue::of)
                                            .collect(Collectors.toList())
                            )))));
        }
        for (Suggestion suggestion : suggestionResults.getSuggestions()) {
            for (Option option : suggestion.getValues()) {
                aggregations.put(option.getTerm(), Query.of(q -> q.simpleQueryString(simpleQuery -> simpleQuery.query(option.getTerm()))));
            }
        }

        FiltersAggregation fs = FiltersAggregation.of(f -> f.filters(bc -> bc.keyed(aggregations)));
        searchBuilder.aggregations("query_counts", Aggregation.of(a -> a.filters(fs)));
        return searchBuilder
                .query(boolBuilder.build()._toQuery()).build();
    }

    public static SearchRequest generateSearchRequest(UserInfo userInfo, SearchQuery searchQuery) {
        ValidateArgument.required(searchQuery, "searchQuery");

        List<String> terms = searchQuery.getQueryTerm();
        List<KeyValue> booleanQueries = searchQuery.getBooleanQuery();
        List<KeyRange> rangeQueries = searchQuery.getRangeQuery();
        List<SearchFacetOption> searchFacetOptions = searchQuery.getFacetOptions();
        Set<Long> userGroups = getAuthorizedUserGroups(userInfo);
        Map<String, Aggregation> aggregations = new HashMap<>();

        BoolQuery.Builder boolBuilder = new BoolQuery.Builder();
        SearchRequest.Builder searchBuilder = new SearchRequest.Builder()
                .index(SearchConstants.OPEN_SEARCH_INDEX_NAME);

        // clean up empty q
        if (terms != null && terms.size() == 1 && "".equals(terms.get(0))) {
            terms = null;
        }

        // check for minimum search requirements
        if (CollectionUtils.isEmpty(terms) && CollectionUtils.isEmpty(booleanQueries)) {
            throw new IllegalArgumentException(
                    "Either one queryTerm or one booleanQuery must be defined.");
        }

        if (!CollectionUtils.isEmpty(terms)) {
            String queryTerms = String.join(" ", terms);
            boolBuilder.must(m -> m.simpleQueryString(sqs -> sqs.query(queryTerms)));
        } else {
            // If no terms, use match_all
            boolBuilder.must(
                    MatchAllQuery.of(m -> m)._toQuery()
            );
        }

        if (!CollectionUtils.isEmpty(booleanQueries)) {
            booleanQueries.forEach(query -> {
                Query termQuery = TermQuery.of(t -> t
                        .field(query.getKey())
                        .value(FieldValue.of(query.getValue()))
                )._toQuery();
                if (query.getNot() != null && query.getNot()) {
                    // Add to must_not if this is a NOT condition
                    boolBuilder.mustNot(termQuery);
                } else {
                    boolBuilder.filter(termQuery);
                }
            });
        }

        if (!CollectionUtils.isEmpty(rangeQueries)) {

            rangeQueries.forEach(query -> {
                ValidateArgument.required(query.getKey(), "keyRange key");
                if (query.getMax() == null && query.getMin() == null) {
                    throw new IllegalArgumentException("At least one of min or max for key=" + query.getKey() + " must be not null.");
                }

                RangeQuery.Builder builder = new RangeQuery.Builder().field(query.getKey());
                if (query.getMin() != null) {
                    builder.gte(JsonData.of(query.getMin()));
                }
                if (query.getMax() != null) {
                    builder.lte(JsonData.of(query.getMax()));
                }

                boolBuilder.filter(Query.of(q -> q.range(builder.build())));

            });
        }

        if (!CollectionUtils.isEmpty(userGroups)) {
            boolBuilder.filter(
                    Query.of(tq -> tq.terms(t -> t
                            .field(FIELD_ACL)
                            .terms(queryTerm -> queryTerm.value(
                                    userGroups.stream()
                                            .map(FieldValue::of)
                                            .collect(Collectors.toList())
                            )))));
        }

        if (!CollectionUtils.isEmpty(searchFacetOptions)) {
            // in cloud search we check if its facet-able or not
            searchFacetOptions.forEach(facet -> {
                SortOrder order = SortOrder.Desc;
                if (SearchFacetSort.ALPHA == facet.getSortType()) {
                    order = SortOrder.Asc;
                }
                SortOrder finalOrder = order;
                // facet filed value should be exactly same as it is in DocumentField class
                String filedValue = SynapseToOpenSearchAggregationField.openSearchFieldFor(facet.getName());
                aggregations.put(filedValue, Aggregation.of(a -> a
                        .terms(t -> t
                                .field(filedValue)
                                .size(Math.toIntExact(facet.getMaxResultCount()))
                                .order((List.of(Map.of("_count", finalOrder)))))));
            });

            searchBuilder.aggregations(aggregations);
        }

        if (searchQuery.getSize() != null) {
            searchBuilder.size(Math.toIntExact(searchQuery.getSize()));
        }

        if (searchQuery.getStart() != null) {
            searchBuilder.from(Math.toIntExact(searchQuery.getStart()));
        }

        if (!CollectionUtils.isEmpty(searchQuery.getReturnFields())) {
            searchBuilder.source(s -> s
                    .filter(f -> f
                            .includes(searchQuery.getReturnFields())));
        }

        return searchBuilder
                .query(boolBuilder.build()._toQuery()).build();

    }

    public static Set<Long> getAuthorizedUserGroups(UserInfo userInfo) {
        ValidateArgument.required(userInfo, "userInfo");

        if (userInfo.isAdmin()) {
            return Collections.emptySet();
        }

        Set<Long> userGroups = userInfo.getGroups();
        ValidateArgument.requiredNotEmpty(userGroups, "userGroup for " + userInfo.getId());
        return userGroups;
    }


    public static SearchResults convertToSynapseSearchResult(SearchResponse<DocumentFields> response, Integer from) {
        SearchResults synapseSearchResults = new SearchResults();
        synapseSearchResults.setFacets(getFacets(response));
        synapseSearchResults.setStart(from == null ? 0L : Long.valueOf(from));
        synapseSearchResults.setFound(response.hits() == null ? 0L : response.hits().total().value());
        synapseSearchResults.setHits(convertToSynapseHit(response.hits().hits()));
        return synapseSearchResults;
    }

    public static List<Facet> getFacets(SearchResponse<DocumentFields> response) {

        List<Facet> facetList = new ArrayList<>();

        Map<String, Aggregate> aggregationMap = response.aggregations();

        for (Map.Entry<String, Aggregate> entry : aggregationMap.entrySet()) {
            String facetName = entry.getKey();

            Facet facet = new Facet();
            facet.setName(facetName);
            FacetTypeNames facetType = OpenSearchFieldType.fieldType(SynapseToOpenSearchAggregationField.synapseFieldFor(facetName));
            facet.setType(facetType);
            List<FacetConstraint> constraints = new ArrayList<>();

            Aggregate aggregate = entry.getValue();

            if (aggregate.isSterms()) {
                StringTermsAggregate termsAgg = aggregate.sterms();

                for (StringTermsBucket bucket : termsAgg.buckets().array()) {
                    FacetConstraint constraint = new FacetConstraint();
                    constraint.setValue(bucket.key());
                    constraint.setCount(bucket.docCount());
                    constraints.add(constraint);
                }
            } else if (aggregate.isLterms()) {
                LongTermsAggregate termsAgg = aggregate.lterms();

                for (LongTermsBucket bucket : termsAgg.buckets().array()) {
                    constraints.add(new FacetConstraint().setValue(bucket.key()).setCount(bucket.docCount()));
                }
            }

            facet.setConstraints(constraints);
            facetList.add(facet);
        }

        return facetList;
    }

    public static List<org.sagebionetworks.repo.model.search.Hit> convertToSynapseHit(List<Hit<DocumentFields>> hits) {
        List<org.sagebionetworks.repo.model.search.Hit> hitList = new ArrayList<>(hits.size());
        for (Hit<DocumentFields> hit : hits) {
            org.sagebionetworks.repo.model.search.Hit synapseHit = new org.sagebionetworks.repo.model.search.Hit();
            synapseHit.setId(hit.id());
            synapseHit.setCreated_by(hit.source().getCreated_by());
            synapseHit.setCreated_on(hit.source().getCreated_on());
            synapseHit.setDescription(hit.source().getDescription());
            synapseHit.setDiagnosis(hit.source().getDiagnosis());
            synapseHit.setEtag(hit.source().getEtag());
            synapseHit.setModified_by(hit.source().getModified_by());
            synapseHit.setModified_on(hit.source().getModified_on());
            synapseHit.setName(hit.source().getName());
            synapseHit.setNode_type(hit.source().getNode_type());
            synapseHit.setTissue(hit.source().getTissue());
            synapseHit.setConsortium(hit.source().getConsortium());
            synapseHit.setOrgan(hit.source().getOrgan());
            hitList.add(synapseHit);
        }

        return hitList;
    }

}
