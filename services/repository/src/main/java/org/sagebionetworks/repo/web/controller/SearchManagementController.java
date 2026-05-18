package org.sagebionetworks.repo.web.controller;

import static org.sagebionetworks.repo.model.oauth.OAuthScope.modify;
import static org.sagebionetworks.repo.model.oauth.OAuthScope.view;

import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.asynch.AsyncJobId;
import org.sagebionetworks.repo.model.asynch.AsynchronousJobStatus;
import org.sagebionetworks.repo.model.search.SearchQueryResults;
import org.sagebionetworks.repo.model.search.table.BindSearchConfigToEntityRequest;
import org.sagebionetworks.repo.model.search.table.ColumnAnalyzerOverride;
import org.sagebionetworks.repo.model.search.table.ListColumnAnalyzerOverridesRequest;
import org.sagebionetworks.repo.model.search.table.ListColumnAnalyzerOverridesResponse;
import org.sagebionetworks.repo.model.search.table.ListSearchConfigurationsRequest;
import org.sagebionetworks.repo.model.search.table.ListSearchConfigurationsResponse;
import org.sagebionetworks.repo.model.search.table.ListSynonymSetsRequest;
import org.sagebionetworks.repo.model.search.table.ListSynonymSetsResponse;
import org.sagebionetworks.repo.model.search.table.ListTextAnalyzersRequest;
import org.sagebionetworks.repo.model.search.table.ListTextAnalyzersResponse;
import org.sagebionetworks.repo.model.search.table.SearchConfigBinding;
import org.sagebionetworks.repo.model.search.table.SearchConfiguration;
import org.sagebionetworks.repo.model.search.table.SearchIndexQuery;
import org.sagebionetworks.repo.model.search.table.SynonymSet;
import org.sagebionetworks.repo.model.search.table.TextAnalyzer;
import org.sagebionetworks.repo.service.search.ColumnAnalyzerOverrideService;
import org.sagebionetworks.repo.service.search.SearchConfigurationService;
import org.sagebionetworks.repo.service.search.SearchIndexQueryService;
import org.sagebionetworks.repo.service.search.SynonymSetService;
import org.sagebionetworks.repo.service.search.TextAnalyzerService;
import org.sagebionetworks.repo.web.RequiredScope;
import org.sagebionetworks.repo.web.UrlHelpers;
import org.sagebionetworks.repo.web.rest.doc.ControllerInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Build and query <a href="${org.sagebionetworks.repo.model.search.table.SearchIndex}">SearchIndex</a>
 * entities using customizable text analysis.
 *
 * <p>These endpoints are a thin layer over Amazon OpenSearch Serverless. Read the
 * <a href="https://docs.opensearch.org/latest/analyzers/custom-analyzer/">OpenSearch custom analyzer guide</a>
 * once before configuring anything here — the four resources below are direct counterparts of
 * OpenSearch concepts.</p>
 *
 * <h6>Concept map</h6>
 * <table border="1">
 * <tr><th>This API</th><th>OpenSearch</th><th>What it is</th></tr>
 * <tr>
 *   <td><a href="${org.sagebionetworks.repo.model.search.table.TextAnalyzer}">TextAnalyzer</a></td>
 *   <td><a href="https://docs.opensearch.org/latest/analyzers/custom-analyzer/">custom analyzer</a></td>
 *   <td>An ordered pipeline: char filters → tokenizer → token filters. Decides how text becomes searchable terms.</td>
 * </tr>
 * <tr>
 *   <td><a href="${org.sagebionetworks.repo.model.search.table.SynonymSet}">SynonymSet</a></td>
 *   <td><a href="https://docs.opensearch.org/latest/analyzers/token-filters/synonym-graph/">synonym_graph token filter</a></td>
 *   <td>A shareable, ACL'd synonym filter. Referenced by qualified name from any TextAnalyzer's filter chain.</td>
 * </tr>
 * <tr>
 *   <td><a href="${org.sagebionetworks.repo.model.search.table.ColumnAnalyzerOverride}">ColumnAnalyzerOverride</a></td>
 *   <td><a href="https://docs.opensearch.org/latest/field-types/supported-field-types/text/">per-field analyzer + search_analyzer</a></td>
 *   <td>Assigns different analyzers to specific columns, overriding the configuration default.</td>
 * </tr>
 * <tr>
 *   <td><a href="${org.sagebionetworks.repo.model.search.table.SearchConfiguration}">SearchConfiguration</a></td>
 *   <td>composition of the above into the index's <code>settings.analysis</code></td>
 *   <td>Defaults + overrides bundle. Bound to a project (or folder/entity) so descendants inherit.</td>
 * </tr>
 * </table>
 *
 * <h6>Putting it together (concrete example)</h6>
 * <pre>
 * // 1. Define a SynonymSet (one of the four resources):
 * POST /repo/v1/search/synonym/set
 * {
 *   "organizationName": "biomed",
 *   "name": "medical_terms",
 *   "definition": "{\"type\":\"synonym_graph\",\"synonyms\":[\"tumor, neoplasm, cancer\"]}"
 * }
 *
 * // 2. Define a TextAnalyzer that references the SynonymSet by qualified name
 * //    ({organizationName}-{name}) in its filter chain:
 * POST /repo/v1/search/text/analyzer
 * {
 *   "organizationName": "biomed",
 *   "name": "scientific",
 *   "settings": {
 *     "tokenizer":    { "name": "standard" },
 *     "tokenFilters": [],
 *     "indexFilterOrder":  ["lowercase", "biomed-medical_terms"],
 *     "searchFilterOrder": ["lowercase", "biomed-medical_terms"]
 *   }
 * }
 *
 * // 3. Bundle into a SearchConfiguration:
 * POST /repo/v1/search/configuration
 * {
 *   "organizationName": "biomed",
 *   "name": "publications_v1",
 *   "defaultIndexAnalyzer":  "biomed-scientific",
 *   "defaultSearchAnalyzer": "biomed-scientific"
 * }
 *
 * // 4. Bind to a project — every SearchIndex under it inherits:
 * PUT /repo/v1/entity/syn0001/searchconfig/binding
 * { "searchConfigurationId": "9876" }
 * </pre>
 *
 * <h6>Sharing across organizations</h6>
 * <p>All four resource types are <b>publicly readable</b>. Resources are referenced by their
 * qualified name <code>{organizationName}-{resourceName}</code> (e.g.
 * <code>org.sagebionetworks-SCIENTIFIC</code>), so a SearchConfiguration in one organization
 * can mix platform-provided analyzers, another organization's SynonymSet, and the
 * organization's own custom analyzers. Mutations (create / update / delete) require Sage
 * Bionetworks employee status plus the appropriate
 * <a href="${org.sagebionetworks.repo.model.ACCESS_TYPE}">ACCESS_TYPE</a> on the owning
 * <a href="${org.sagebionetworks.repo.model.schema.Organization}">Organization</a>.
 * <code>organizationName</code> and <code>name</code> are immutable after create — to "rename",
 * create a new resource and update any SearchConfigurations that reference the old one.</p>
 *
 * <h6>Built-in OpenSearch names are usable as-is</h6>
 * <p>Filter chains and tokenizer names pass through to OpenSearch verbatim when they don't
 * match a Synapse-managed resource. <code>lowercase</code>, <code>standard</code>,
 * <code>english_stemmer</code>, <code>icu_tokenizer</code>, <code>phonetic</code>, etc. all work
 * without any Synapse-side registration — the
 * <a href="https://docs.aws.amazon.com/opensearch-service/latest/developerguide/serverless-genref.html">analysis-icu, analysis-phonetic, analysis-kuromoji, analysis-nori, analysis-smartcn</a>
 * plugins ship with AOSS. The exception: file-based parameters
 * (<code>stopwords_path</code>, <code>synonyms_path</code>, <code>mappings_path</code>,
 * <code>protected_words_path</code>, and any other <code>*_path</code> key) are <b>not supported in
 * AOSS Serverless</b> and are rejected at create time — use the inline equivalents
 * (<code>stopwords</code>, <code>synonyms</code>, <code>mappings</code>,
 * <code>protected_words</code>).</p>
 *
 * <h6>Querying</h6>
 * <p>Run an async query with
 * <a href="${POST.search.query.async.start}">POST /search/query/async/start</a> + poll
 * <a href="${GET.search.query.async.get.asyncToken}">GET /search/query/async/get/{asyncToken}</a>.
 * For type-ahead use the synchronous
 * <a href="${POST.search.autocomplete}">POST /search/autocomplete</a> (capped at 8 hits, no
 * facets). See <a href="${org.sagebionetworks.repo.model.search.SearchQuery}">SearchQuery</a>
 * for the available knobs (query type, filters, facets, sort, highlight, pagination).</p>
 *
 * <h6>Index limits</h6>
 * <p>Max 500,000 indexed rows per SearchIndex. Query page size defaults to 25, capped at 100.
 * Per-column field type and <code>ignore_above</code> sizing are documented on
 * <a href="${org.sagebionetworks.repo.model.search.table.SearchIndex}">SearchIndex</a>.</p>
 *
 */
@ControllerInfo(displayName = "Search Management Services", path = "repo/v1")
@Controller
@RequestMapping(UrlHelpers.REPO_PATH)
public class SearchManagementController {

	@Autowired
	private TextAnalyzerService textAnalyzerService;

	@Autowired
	private ColumnAnalyzerOverrideService columnAnalyzerOverrideService;

	@Autowired
	private SynonymSetService synonymSetService;

	@Autowired
	private SearchConfigurationService searchConfigurationService;

	@Autowired
	private SearchIndexQueryService searchIndexQueryService;

	// ==================== Text Analyzers ====================

	/**
	 * Create a new <a href="${org.sagebionetworks.repo.model.search.table.TextAnalyzer}">TextAnalyzer</a>
	 * within the specified
	 * <a href="${org.sagebionetworks.repo.model.schema.Organization}">Organization</a>.
	 * <p>
	 * The caller must be a Sage Bionetworks employee with
	 * <a href="${org.sagebionetworks.repo.model.ACCESS_TYPE}">ACCESS_TYPE.CREATE</a>
	 * permission on the Organization.
	 * </p>
	 * <p>
	 * The analyzer's settings are validated against AOSS's
	 * <a href="https://docs.opensearch.org/latest/api-reference/analyze-apis/">_analyze API</a>
	 * before save (3-attempt retry on transient AOSS errors). Invalid tokenizer or filter
	 * configurations return 400 with the AOSS-side reason. File-based parameters
	 * (any <code>*_path</code> key) are rejected at this stage — see
	 * <a href="${org.sagebionetworks.repo.model.search.table.AnalyzerComponent}">AnalyzerComponent</a>.
	 * </p>
	 *
	 * @param userId The ID of the authenticated user.
	 * @param request The text analyzer to create. Must include organizationName, name, and settings.
	 * @return The created text analyzer with a generated ID, etag, and audit timestamps.
	 */
	@RequiredScope({ view, modify })
	@ResponseStatus(HttpStatus.CREATED)
	@RequestMapping(value = UrlHelpers.SEARCH_TEXT_ANALYZER, method = RequestMethod.POST)
	public @ResponseBody TextAnalyzer createTextAnalyzer(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@RequestBody TextAnalyzer request) {
		return textAnalyzerService.create(userId, request);
	}

	/**
	 * Get a <a href="${org.sagebionetworks.repo.model.search.table.TextAnalyzer}">TextAnalyzer</a>
	 * by its ID.
	 * <p>
	 * This is a public read operation &mdash; no special authorization is required.
	 * </p>
	 *
	 * @param userId The ID of the authenticated user.
	 * @param id The numeric ID of the text analyzer to retrieve.
	 * @return The requested text analyzer.
	 * @throws NotFoundException If no text analyzer exists with the given ID.
	 */
	@RequiredScope({ view })
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = UrlHelpers.SEARCH_TEXT_ANALYZER_ID, method = RequestMethod.GET)
	public @ResponseBody TextAnalyzer getTextAnalyzer(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@PathVariable Long id) {
		return textAnalyzerService.get(userId, id);
	}

	/**
	 * Update a <a href="${org.sagebionetworks.repo.model.search.table.TextAnalyzer}">TextAnalyzer</a>.
	 * <p>
	 * The caller must be a Sage Bionetworks employee with
	 * <a href="${org.sagebionetworks.repo.model.ACCESS_TYPE}">ACCESS_TYPE.UPDATE</a>
	 * permission on the Organization. The <code>organizationName</code> and <code>name</code>
	 * are immutable and cannot be changed after creation.
	 * </p>
	 * <p>
	 * Settings are re-validated against AOSS, including the file-based-parameter check.
	 * Concurrency is managed via the etag field. If the etag in the request does not match
	 * the current etag, a 409 Conflict is returned.
	 * </p>
	 *
	 * @param userId The ID of the authenticated user.
	 * @param id The path ID of the text analyzer (must match the request body's ID).
	 * @param request The updated text analyzer.
	 * @return The updated text analyzer with a new etag.
	 * @throws NotFoundException If no text analyzer exists with the given ID.
	 */
	@RequiredScope({ view, modify })
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = UrlHelpers.SEARCH_TEXT_ANALYZER_ID, method = RequestMethod.PUT)
	public @ResponseBody TextAnalyzer updateTextAnalyzer(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@PathVariable Long id,
			@RequestBody TextAnalyzer request) {
		String idString = String.valueOf(id);
		if (!idString.equals(request.getId())) {
			throw new IllegalArgumentException(
				"The path ID: " + idString + " does not match the request body's ID: " + request.getId());
		}
		return textAnalyzerService.update(userId, request);
	}

	/**
	 * List <a href="${org.sagebionetworks.repo.model.search.table.TextAnalyzer}">TextAnalyzer</a>
	 * objects, optionally filtered by Organization.
	 * <p>
	 * This is a public read operation. Results are paginated using a next page token.
	 * If <code>organizationName</code> is null, all text analyzers across all Organizations are returned.
	 * </p>
	 *
	 * @param userId The ID of the authenticated user.
	 * @param request The list request. Set organizationName to filter by Organization,
	 *        or leave null to list all. Use nextPageToken for pagination.
	 * @return A paginated list of text analyzers.
	 */
	@RequiredScope({ view })
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = UrlHelpers.SEARCH_TEXT_ANALYZER_LIST, method = RequestMethod.POST)
	public @ResponseBody ListTextAnalyzersResponse listTextAnalyzers(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@RequestBody ListTextAnalyzersRequest request) {
		return textAnalyzerService.list(userId, request);
	}

	// ==================== Column Analyzer Overrides ====================

	/**
	 * Create a new <a href="${org.sagebionetworks.repo.model.search.table.ColumnAnalyzerOverride}">ColumnAnalyzerOverride</a>
	 * within the specified
	 * <a href="${org.sagebionetworks.repo.model.schema.Organization}">Organization</a>.
	 * <p>
	 * The caller must be a Sage Bionetworks employee with
	 * <a href="${org.sagebionetworks.repo.model.ACCESS_TYPE}">ACCESS_TYPE.CREATE</a>
	 * permission on the Organization.
	 * </p>
	 *
	 * @param userId The ID of the authenticated user.
	 * @param request The column analyzer override to create. Must include organizationName, name,
	 *        and at least one override entry mapping a column to an analyzer pair.
	 * @return The created column analyzer override with a generated ID, etag, and audit timestamps.
	 */
	@RequiredScope({ view, modify })
	@ResponseStatus(HttpStatus.CREATED)
	@RequestMapping(value = UrlHelpers.SEARCH_COLUMN_ANALYZER_OVERRIDE, method = RequestMethod.POST)
	public @ResponseBody ColumnAnalyzerOverride createColumnAnalyzerOverride(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@RequestBody ColumnAnalyzerOverride request) {
		return columnAnalyzerOverrideService.create(userId, request);
	}

	/**
	 * Get a <a href="${org.sagebionetworks.repo.model.search.table.ColumnAnalyzerOverride}">ColumnAnalyzerOverride</a>
	 * by its ID.
	 * <p>
	 * This is a public read operation &mdash; no special authorization is required.
	 * </p>
	 *
	 * @param userId The ID of the authenticated user.
	 * @param columnAnalyzerOverrideId The ID of the column analyzer override to retrieve.
	 * @return The requested column analyzer override.
	 * @throws NotFoundException If no column analyzer override exists with the given ID.
	 */
	@RequiredScope({ view })
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = UrlHelpers.SEARCH_COLUMN_ANALYZER_OVERRIDE_ID, method = RequestMethod.GET)
	public @ResponseBody ColumnAnalyzerOverride getColumnAnalyzerOverride(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@PathVariable String columnAnalyzerOverrideId) {
		return columnAnalyzerOverrideService.get(userId, columnAnalyzerOverrideId);
	}

	/**
	 * Update a <a href="${org.sagebionetworks.repo.model.search.table.ColumnAnalyzerOverride}">ColumnAnalyzerOverride</a>.
	 * <p>
	 * The caller must be a Sage Bionetworks employee with
	 * <a href="${org.sagebionetworks.repo.model.ACCESS_TYPE}">ACCESS_TYPE.UPDATE</a>
	 * permission on the Organization. The <code>organizationName</code> and <code>name</code>
	 * are immutable and cannot be changed after creation.
	 * </p>
	 * <p>
	 * Concurrency is managed via the etag field. If the etag in the request does not match
	 * the current etag, a 409 Conflict is returned.
	 * </p>
	 *
	 * @param userId The ID of the authenticated user.
	 * @param columnAnalyzerOverrideId The path ID (must match the request body's ID).
	 * @param request The updated column analyzer override.
	 * @return The updated column analyzer override with a new etag.
	 * @throws NotFoundException If no column analyzer override exists with the given ID.
	 */
	@RequiredScope({ view, modify })
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = UrlHelpers.SEARCH_COLUMN_ANALYZER_OVERRIDE_ID, method = RequestMethod.PUT)
	public @ResponseBody ColumnAnalyzerOverride updateColumnAnalyzerOverride(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@PathVariable String columnAnalyzerOverrideId,
			@RequestBody ColumnAnalyzerOverride request) {
		if (!columnAnalyzerOverrideId.equals(request.getId())) {
			throw new IllegalArgumentException(
				"The path ID: " + columnAnalyzerOverrideId + " does not match the request body's ID: " + request.getId());
		}
		return columnAnalyzerOverrideService.update(userId, request);
	}

	/**
	 * List <a href="${org.sagebionetworks.repo.model.search.table.ColumnAnalyzerOverride}">ColumnAnalyzerOverride</a>
	 * objects, optionally filtered by Organization.
	 * <p>
	 * This is a public read operation. Results are paginated using a next page token.
	 * If <code>organizationName</code> is null, all column analyzer overrides across all Organizations are returned.
	 * </p>
	 *
	 * @param userId The ID of the authenticated user.
	 * @param request The list request. Set organizationName to filter by Organization,
	 *        or leave null to list all. Use nextPageToken for pagination.
	 * @return A paginated list of column analyzer overrides.
	 */
	@RequiredScope({ view })
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = UrlHelpers.SEARCH_COLUMN_ANALYZER_OVERRIDE_LIST, method = RequestMethod.POST)
	public @ResponseBody ListColumnAnalyzerOverridesResponse listColumnAnalyzerOverrides(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@RequestBody ListColumnAnalyzerOverridesRequest request) {
		return columnAnalyzerOverrideService.list(userId, request);
	}

	// ==================== Synonym Sets ====================

	/**
	 * Create a new <a href="${org.sagebionetworks.repo.model.search.table.SynonymSet}">SynonymSet</a>
	 * within the specified
	 * <a href="${org.sagebionetworks.repo.model.schema.Organization}">Organization</a>.
	 * <p>
	 * The caller must be a Sage Bionetworks employee with
	 * <a href="${org.sagebionetworks.repo.model.ACCESS_TYPE}">ACCESS_TYPE.CREATE</a>
	 * permission on the Organization.
	 * </p>
	 * <p>
	 * The supplied <code>definition</code> is checked for AOSS-incompatible file-based
	 * parameters at create time — any key ending in <code>_path</code> (e.g.
	 * <code>synonyms_path</code>) is rejected with 400. Supply the synonym list inline via
	 * the <code>synonyms</code> array — see
	 * <a href="${org.sagebionetworks.repo.model.search.table.SynonymSet}">SynonymSet</a>.
	 * </p>
	 *
	 * @param userId The ID of the authenticated user.
	 * @param request The synonym set to create. Must include organizationName, name, and definition.
	 * @return The created synonym set with a generated ID, etag, and audit timestamps.
	 */
	@RequiredScope({ view, modify })
	@ResponseStatus(HttpStatus.CREATED)
	@RequestMapping(value = UrlHelpers.SEARCH_SYNONYM_SET, method = RequestMethod.POST)
	public @ResponseBody SynonymSet createSynonymSet(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@RequestBody SynonymSet request) {
		return synonymSetService.create(userId, request);
	}

	/**
	 * Get a <a href="${org.sagebionetworks.repo.model.search.table.SynonymSet}">SynonymSet</a>
	 * by its ID.
	 * <p>
	 * This is a public read operation &mdash; no special authorization is required.
	 * </p>
	 *
	 * @param userId The ID of the authenticated user.
	 * @param synonymSetId The ID of the synonym set to retrieve.
	 * @return The requested synonym set.
	 * @throws NotFoundException If no synonym set exists with the given ID.
	 */
	@RequiredScope({ view })
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = UrlHelpers.SEARCH_SYNONYM_SET_ID, method = RequestMethod.GET)
	public @ResponseBody SynonymSet getSynonymSet(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@PathVariable String synonymSetId) {
		return synonymSetService.get(userId, synonymSetId);
	}

	/**
	 * Update a <a href="${org.sagebionetworks.repo.model.search.table.SynonymSet}">SynonymSet</a>.
	 * <p>
	 * The caller must be a Sage Bionetworks employee with
	 * <a href="${org.sagebionetworks.repo.model.ACCESS_TYPE}">ACCESS_TYPE.UPDATE</a>
	 * permission on the Organization. The <code>organizationName</code> and <code>name</code>
	 * are immutable and cannot be changed after creation.
	 * </p>
	 * <p>
	 * The new <code>definition</code> is re-checked for AOSS-incompatible file-based
	 * parameters (any <code>*_path</code> key). Concurrency is managed via the etag
	 * field; an etag mismatch returns 409 Conflict.
	 * </p>
	 *
	 * @param userId The ID of the authenticated user.
	 * @param synonymSetId The path ID (must match the request body's ID).
	 * @param request The updated synonym set.
	 * @return The updated synonym set with a new etag.
	 * @throws NotFoundException If no synonym set exists with the given ID.
	 */
	@RequiredScope({ view, modify })
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = UrlHelpers.SEARCH_SYNONYM_SET_ID, method = RequestMethod.PUT)
	public @ResponseBody SynonymSet updateSynonymSet(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@PathVariable String synonymSetId,
			@RequestBody SynonymSet request) {
		if (!synonymSetId.equals(request.getId())) {
			throw new IllegalArgumentException(
				"The path ID: " + synonymSetId + " does not match the request body's ID: " + request.getId());
		}
		return synonymSetService.update(userId, request);
	}

	/**
	 * List <a href="${org.sagebionetworks.repo.model.search.table.SynonymSet}">SynonymSet</a>
	 * objects, optionally filtered by Organization.
	 * <p>
	 * This is a public read operation. Results are paginated using a next page token.
	 * If <code>organizationName</code> is null, all synonym sets across all Organizations are returned.
	 * </p>
	 *
	 * @param userId The ID of the authenticated user.
	 * @param request The list request. Set organizationName to filter by Organization,
	 *        or leave null to list all. Use nextPageToken for pagination.
	 * @return A paginated list of synonym sets.
	 */
	@RequiredScope({ view })
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = UrlHelpers.SEARCH_SYNONYM_SET_LIST, method = RequestMethod.POST)
	public @ResponseBody ListSynonymSetsResponse listSynonymSets(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@RequestBody ListSynonymSetsRequest request) {
		return synonymSetService.list(userId, request);
	}

	// ==================== Search Configurations ====================

	/**
	 * Create a new <a href="${org.sagebionetworks.repo.model.search.table.SearchConfiguration}">SearchConfiguration</a>
	 * within the specified
	 * <a href="${org.sagebionetworks.repo.model.schema.Organization}">Organization</a>.
	 * <p>
	 * The caller must be a Sage Bionetworks employee with
	 * <a href="${org.sagebionetworks.repo.model.ACCESS_TYPE}">ACCESS_TYPE.CREATE</a>
	 * permission on the Organization.
	 * </p>
	 *
	 * @param userId The ID of the authenticated user.
	 * @param request The search configuration to create. Must include organizationName, name,
	 *        defaultIndexAnalyzer, and defaultSearchAnalyzer.
	 * @return The created search configuration with a generated ID, etag, and audit timestamps.
	 */
	@RequiredScope({ view, modify })
	@ResponseStatus(HttpStatus.CREATED)
	@RequestMapping(value = UrlHelpers.SEARCH_CONFIGURATION, method = RequestMethod.POST)
	public @ResponseBody SearchConfiguration createSearchConfiguration(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@RequestBody SearchConfiguration request) {
		return searchConfigurationService.create(userId, request);
	}

	/**
	 * Get a <a href="${org.sagebionetworks.repo.model.search.table.SearchConfiguration}">SearchConfiguration</a>
	 * by its ID.
	 * <p>
	 * This is a public read operation &mdash; no special authorization is required.
	 * </p>
	 *
	 * @param userId The ID of the authenticated user.
	 * @param searchConfigurationId The ID of the search configuration to retrieve.
	 * @return The requested search configuration.
	 * @throws NotFoundException If no search configuration exists with the given ID.
	 */
	@RequiredScope({ view })
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = UrlHelpers.SEARCH_CONFIGURATION_ID, method = RequestMethod.GET)
	public @ResponseBody SearchConfiguration getSearchConfiguration(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@PathVariable String searchConfigurationId) {
		return searchConfigurationService.get(userId, searchConfigurationId);
	}

	/**
	 * Update a <a href="${org.sagebionetworks.repo.model.search.table.SearchConfiguration}">SearchConfiguration</a>.
	 * <p>
	 * The caller must be a Sage Bionetworks employee with
	 * <a href="${org.sagebionetworks.repo.model.ACCESS_TYPE}">ACCESS_TYPE.UPDATE</a>
	 * permission on the Organization. The <code>organizationName</code> and <code>name</code>
	 * are immutable and cannot be changed after creation.
	 * </p>
	 * <p>
	 * Concurrency is managed via the etag field. If the etag in the request does not match
	 * the current etag, a 409 Conflict is returned.
	 * </p>
	 *
	 * @param userId The ID of the authenticated user.
	 * @param searchConfigurationId The path ID (must match the request body's ID).
	 * @param request The updated search configuration.
	 * @return The updated search configuration with a new etag.
	 * @throws NotFoundException If no search configuration exists with the given ID.
	 */
	@RequiredScope({ view, modify })
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = UrlHelpers.SEARCH_CONFIGURATION_ID, method = RequestMethod.PUT)
	public @ResponseBody SearchConfiguration updateSearchConfiguration(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@PathVariable String searchConfigurationId,
			@RequestBody SearchConfiguration request) {
		if (!searchConfigurationId.equals(request.getId())) {
			throw new IllegalArgumentException(
				"The path ID: " + searchConfigurationId + " does not match the request body's ID: " + request.getId());
		}
		return searchConfigurationService.update(userId, request);
	}

	/**
	 * List <a href="${org.sagebionetworks.repo.model.search.table.SearchConfiguration}">SearchConfiguration</a>
	 * objects, optionally filtered by Organization.
	 * <p>
	 * This is a public read operation. Results are paginated using a next page token.
	 * If <code>organizationName</code> is null, all search configurations across all Organizations are returned.
	 * </p>
	 *
	 * @param userId The ID of the authenticated user.
	 * @param request The list request. Set organizationName to filter by Organization,
	 *        or leave null to list all. Use nextPageToken for pagination.
	 * @return A paginated list of search configurations.
	 */
	@RequiredScope({ view })
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = UrlHelpers.SEARCH_CONFIGURATION_LIST, method = RequestMethod.POST)
	public @ResponseBody ListSearchConfigurationsResponse listSearchConfigurations(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@RequestBody ListSearchConfigurationsRequest request) {
		return searchConfigurationService.list(userId, request);
	}

	// ==================== Search Configuration Bindings ====================

	/**
	 * Bind a <a href="${org.sagebionetworks.repo.model.search.table.SearchConfiguration}">SearchConfiguration</a>
	 * to an entity (typically a project). The caller must have EDIT permission on the entity.
	 * Replaces any existing binding on that entity. Descendant SearchIndex entities inherit the
	 * configuration unless they set their own <code>searchConfigurationId</code>; the effective
	 * configuration for any entity is resolved by walking up the hierarchy
	 * (entity → folder → project).
	 *
	 * @param userId The ID of the authenticated user.
	 * @param entityId The ID of the entity to bind to.
	 * @param request The bind request containing the searchConfigurationId.
	 * @return The created (or replaced) binding.
	 */
	@RequiredScope({ view, modify })
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = UrlHelpers.ENTITY_SEARCH_CONFIG_BINDING, method = RequestMethod.PUT)
	public @ResponseBody SearchConfigBinding bindSearchConfigToEntity(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@PathVariable String entityId,
			@RequestBody BindSearchConfigToEntityRequest request) {
		request.setEntityId(entityId);
		return searchConfigurationService.bindSearchConfigToEntity(userId, request);
	}

	/**
	 * Get the effective <a href="${org.sagebionetworks.repo.model.search.table.SearchConfigBinding}">SearchConfigBinding</a>
	 * for an entity. Walks up the entity hierarchy (entity → folder → project) and returns the
	 * first binding found on the entity or any ancestor.
	 * <p>
	 * This is a public read operation &mdash; no special authorization on the binding is required.
	 * </p>
	 *
	 * @param userId The ID of the authenticated user.
	 * @param entityId The ID of the entity to look up.
	 * @return The effective binding.
	 * @throws NotFoundException If no binding exists on the entity or any ancestor.
	 */
	@RequiredScope({ view })
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = UrlHelpers.ENTITY_SEARCH_CONFIG_BINDING, method = RequestMethod.GET)
	public @ResponseBody SearchConfigBinding getSearchConfigBinding(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@PathVariable String entityId) {
		return searchConfigurationService.getSearchConfigBinding(userId, entityId);
	}

	/**
	 * Clear the <a href="${org.sagebionetworks.repo.model.search.table.SearchConfigBinding}">SearchConfigBinding</a>
	 * on a specific entity. Does not affect ancestor bindings — descendants that were inheriting
	 * via the cleared binding will fall back to the next binding up the hierarchy. The caller
	 * must have EDIT permission on the entity.
	 *
	 * @param userId The ID of the authenticated user.
	 * @param entityId The ID of the entity whose binding to clear.
	 * @throws NotFoundException If no binding exists directly on this entity.
	 */
	@RequiredScope({ view, modify })
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@RequestMapping(value = UrlHelpers.ENTITY_SEARCH_CONFIG_BINDING, method = RequestMethod.DELETE)
	public void clearSearchConfigBinding(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@PathVariable String entityId) {
		searchConfigurationService.clearSearchConfigBinding(userId, entityId);
	}

	// ==================== Search Queries ====================

	/**
	 * Start an asynchronous search query job against a
	 * <a href="${org.sagebionetworks.repo.model.search.table.SearchIndex}">SearchIndex</a>.
	 * <p>
	 * The request wraps a
	 * <a href="${org.sagebionetworks.repo.model.search.SearchQuery}">SearchQuery</a>
	 * — see that schema for the full set of available knobs. Highlights:
	 * </p>
	 * <ul>
	 *   <li><b>queryType</b> — the full-text query model. See
	 *       <a href="${org.sagebionetworks.repo.model.search.SearchQueryType}">SearchQueryType</a>
	 *       for the per-type explanation of scoring, accepted parameters, and when to use each.
	 *       Default is <code>SIMPLE_QUERY_STRING</code>. An empty or null <code>queryText</code>
	 *       automatically selects <code>MATCH_ALL</code>.</li>
	 *   <li><b>queryFields</b> — restrict the search to specific columns, with optional per-field
	 *       boost using <code>column^N</code> syntax (e.g. <code>"title^3"</code>). Empty means
	 *       all indexed fields.</li>
	 *   <li><b>Filters</b> — <code>termsFilters</code>, <code>rangeFilters</code>,
	 *       <code>existsFilters</code>, and <code>notExistsFilters</code> narrow the result set.
	 *       All filters run in non-scoring context (yes/no matching, cacheable) — they do not
	 *       contribute to relevance.</li>
	 *   <li><b>Facets</b> — <code>facetRequests</code> produce bucket aggregations per column.
	 *       Sort by <code>COUNT</code> or <code>KEY</code> in either direction; cap each facet
	 *       with <code>maxValueCount</code>.</li>
	 *   <li><b>Response tuning</b> — <code>returnFields</code>, <code>sort</code> (including the
	 *       special <code>_score</code> pseudo-column), <code>offset</code>,
	 *       <code>limit</code> (capped at 100), and <code>highlight</code>.</li>
	 * </ul>
	 * <p>
	 * Results are returned as a
	 * <a href="${org.sagebionetworks.repo.model.search.SearchQueryResults}">SearchQueryResults</a>.
	 * Use <a href="${GET.search.query.async.get.asyncToken}">GET /search/query/async/get/{asyncToken}</a>
	 * to poll for results — while the job is running the GET returns HTTP 202 with a
	 * <a href="${org.sagebionetworks.repo.model.asynch.AsynchronousJobStatus}">AsynchronousJobStatus</a>.
	 * </p>
	 * <p>
	 * The caller must have <code>READ</code> access to the source table or view that backs the
	 * SearchIndex. Row-level access is enforced automatically: rows the caller cannot read are
	 * filtered out of the results before they leave the server.
	 * </p>
	 *
	 * @param userId The ID of the authenticated user.
	 * @param request The search query request including the <code>searchIndexId</code> and the
	 *                embedded <code>SearchQuery</code>.
	 * @return An async job token. Poll
	 *         <a href="${GET.search.query.async.get.asyncToken}">GET /search/query/async/get/{asyncToken}</a>
	 *         for the final results.
	 */
	@RequiredScope({ view })
	@ResponseStatus(HttpStatus.CREATED)
	@RequestMapping(value = UrlHelpers.SEARCH_QUERY_ASYNC_START, method = RequestMethod.POST)
	public @ResponseBody AsyncJobId startQuery(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@RequestBody SearchIndexQuery request) {
		AsynchronousJobStatus job = searchIndexQueryService.startSearchQuery(userId, request);
		AsyncJobId asyncJobId = new AsyncJobId();
		asyncJobId.setToken(job.getJobId());
		return asyncJobId;
	}

	/**
	 * Get the results of a previously started asynchronous search query.
	 * <p>
	 * Note: When the result is not ready yet, this method will return a status
	 * code of 202 (ACCEPTED) and the response body will be a
	 * <a href="${org.sagebionetworks.repo.model.asynch.AsynchronousJobStatus}">AsynchronousJobStatus</a> object.
	 * </p>
	 *
	 * @param userId The ID of the authenticated user.
	 * @param asyncToken The token returned by the start query endpoint.
	 * @return The search query results.
	 * @throws Throwable
	 */
	@RequiredScope({ view })
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = UrlHelpers.SEARCH_QUERY_ASYNC_GET, method = RequestMethod.GET)
	public @ResponseBody SearchQueryResults getQueryResults(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@PathVariable String asyncToken) throws Throwable {
		AsynchronousJobStatus jobStatus = searchIndexQueryService.getSearchQueryResults(userId, asyncToken);
		return (SearchQueryResults) jobStatus.getResponseBody();
	}

	/**
	 * Perform a synchronous autocomplete search query against a
	 * <a href="${org.sagebionetworks.repo.model.search.table.SearchIndex}">SearchIndex</a>.
	 * <p>
	 * This endpoint is purpose-built for type-ahead input: it overrides any supplied
	 * <code>queryType</code> with <code>PREFIX</code> (see
	 * <a href="${org.sagebionetworks.repo.model.search.SearchQueryType}">SearchQueryType</a>
	 * for the full description) and caps <code>limit</code> at 8 to keep responses small
	 * and latency low. Caller-supplied <code>facetRequests</code> and
	 * <code>highlight</code> are ignored — autocomplete returns matching hits only.
	 * </p>
	 * <p>
	 * For anything that needs scored relevance, faceting, or result counts, use the async
	 * <a href="${POST.search.query.async.start}">POST /search/query/async/start</a> endpoint
	 * instead.
	 * </p>
	 *
	 * @param userId The ID of the authenticated user.
	 * @param request The search query request including the <code>searchIndexId</code>.
	 * @return The autocomplete results (up to 8 hits).
	 */
	@RequiredScope({ view })
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = UrlHelpers.SEARCH_AUTOCOMPLETE, method = RequestMethod.POST)
	public @ResponseBody SearchQueryResults autocomplete(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@RequestBody SearchIndexQuery request) {
		return searchIndexQueryService.autocomplete(userId, request);
	}
}
