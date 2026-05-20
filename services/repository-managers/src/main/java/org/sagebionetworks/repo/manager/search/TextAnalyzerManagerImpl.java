package org.sagebionetworks.repo.manager.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.sagebionetworks.repo.model.ACCESS_TYPE;
import org.sagebionetworks.repo.model.AccessControlListDAO;
import org.sagebionetworks.repo.model.AuthorizationUtils;
import org.sagebionetworks.repo.model.NextPageToken;
import org.sagebionetworks.repo.model.ObjectType;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dbo.schema.OrganizationDao;
import org.sagebionetworks.repo.model.dbo.search.SynonymSetDao;
import org.sagebionetworks.repo.model.dbo.search.TextAnalyzerDao;
import org.sagebionetworks.repo.model.search.table.ListTextAnalyzersRequest;
import org.sagebionetworks.repo.model.search.table.ListTextAnalyzersResponse;
import org.sagebionetworks.repo.model.search.table.SynonymSet;
import org.sagebionetworks.repo.model.search.table.TextAnalyzer;
import org.sagebionetworks.repo.transactions.WriteTransaction;
import org.sagebionetworks.repo.web.NotFoundException;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;

@Service
public class TextAnalyzerManagerImpl implements TextAnalyzerManager {

	private static final String MSG_UNAUTHORIZED = "Only Sage Bionetworks employees can manage text analyzers.";

	private final TextAnalyzerDao textAnalyzerDao;
	private final AccessControlListDAO aclDao;
	private final OrganizationDao organizationDao;
	private final SynonymSetDao synonymSetDao;
	private final OpenSearchManager openSearchManager;

	public TextAnalyzerManagerImpl(TextAnalyzerDao textAnalyzerDao, AccessControlListDAO aclDao,
			OrganizationDao organizationDao, SynonymSetDao synonymSetDao,
			OpenSearchManager openSearchManager) {
		this.textAnalyzerDao = textAnalyzerDao;
		this.aclDao = aclDao;
		this.organizationDao = organizationDao;
		this.synonymSetDao = synonymSetDao;
		this.openSearchManager = openSearchManager;
	}

	@Override
	@WriteTransaction
	public TextAnalyzer create(UserInfo user, TextAnalyzer analyzer) {
		ValidateArgument.required(user, "user");
		ValidateArgument.required(analyzer, "analyzer");
		ValidateArgument.requiredNotBlank(analyzer.getOrganizationName(), "organizationName");
		ValidateArgument.requiredNotBlank(analyzer.getName(), "name");
		ValidateArgument.requiredNotBlank(analyzer.getSettings(), "settings");
		SearchResourceConstants.validateResourceName(analyzer.getName());

		AuthorizationUtils.disallowAnonymous(user);
		if (!AuthorizationUtils.isSageEmployeeOrAdmin(user)) {
			throw new UnauthorizedException(MSG_UNAUTHORIZED);
		}
		if (!user.isAdmin()) {
			aclDao.canAccess(user, resolveOrganizationId(analyzer.getOrganizationName()), ObjectType.ORGANIZATION, ACCESS_TYPE.CREATE)
				.checkAuthorizationOrElseThrow();
		}

		validateSettings(analyzer.getSettings());

		return textAnalyzerDao.create(analyzer, user.getId());
	}

	@Override
	public TextAnalyzer get(UserInfo user, Long id) {
		ValidateArgument.required(id, "id");

		return textAnalyzerDao.get(id)
				.orElseThrow(() -> new NotFoundException("TextAnalyzer with id '" + id + "' does not exist."));
	}

	@Override
	@WriteTransaction
	public TextAnalyzer update(UserInfo user, TextAnalyzer analyzer) {
		ValidateArgument.required(user, "user");
		ValidateArgument.required(analyzer, "analyzer");
		ValidateArgument.requiredNotBlank(analyzer.getId(), "id");
		ValidateArgument.requiredNotBlank(analyzer.getOrganizationName(), "organizationName");
		ValidateArgument.requiredNotBlank(analyzer.getName(), "name");
		ValidateArgument.requiredNotBlank(analyzer.getSettings(), "settings");
		SearchResourceConstants.validateResourceName(analyzer.getName());

		AuthorizationUtils.disallowAnonymous(user);
		if (!AuthorizationUtils.isSageEmployeeOrAdmin(user)) {
			throw new UnauthorizedException(MSG_UNAUTHORIZED);
		}
		Long id = Long.parseLong(analyzer.getId());
		TextAnalyzer existing = textAnalyzerDao.get(id)
			.orElseThrow(() -> new NotFoundException("TextAnalyzer with id '" + analyzer.getId() + "' does not exist."));

		if (!existing.getOrganizationName().equals(analyzer.getOrganizationName())) {
			throw new IllegalArgumentException(SearchResourceConstants.ORG_NAME_IMMUTABLE_MSG);
		}
		if (!existing.getName().equals(analyzer.getName())) {
			throw new IllegalArgumentException(SearchResourceConstants.NAME_IMMUTABLE_MSG);
		}

		if (!user.isAdmin()) {
			aclDao.canAccess(user, resolveOrganizationId(existing.getOrganizationName()), ObjectType.ORGANIZATION, ACCESS_TYPE.UPDATE)
				.checkAuthorizationOrElseThrow();
		}

		validateSettings(analyzer.getSettings());

		return textAnalyzerDao.update(analyzer, user.getId());
	}

	@Override
	@WriteTransaction
	public void delete(UserInfo user, Long id) {
		ValidateArgument.required(user, "user");
		ValidateArgument.required(id, "id");

		AuthorizationUtils.disallowAnonymous(user);
		if (!AuthorizationUtils.isSageEmployeeOrAdmin(user)) {
			throw new UnauthorizedException(MSG_UNAUTHORIZED);
		}

		TextAnalyzer existing = textAnalyzerDao.get(id)
			.orElseThrow(() -> new NotFoundException("TextAnalyzer with id '" + id + "' does not exist."));

		if (!user.isAdmin()) {
			aclDao.canAccess(user, resolveOrganizationId(existing.getOrganizationName()), ObjectType.ORGANIZATION, ACCESS_TYPE.DELETE)
				.checkAuthorizationOrElseThrow();
		}

		try {
			textAnalyzerDao.delete(id);
		} catch (DataIntegrityViolationException e) {
			throw new IllegalArgumentException(
				"Cannot delete text analyzer '" + id + "' because it is still referenced.", e);
		}
	}

	@Override
	public ListTextAnalyzersResponse list(UserInfo user, ListTextAnalyzersRequest request) {
		ValidateArgument.required(request, "request");

		NextPageToken nextPageToken = new NextPageToken(request.getNextPageToken());

		List<TextAnalyzer> page;
		if (request.getOrganizationName() == null) {
			page = textAnalyzerDao.listAll(nextPageToken.getLimitForQuery(), nextPageToken.getOffset());
		} else {
			page = textAnalyzerDao.listByOrganization(
					request.getOrganizationName(), nextPageToken.getLimitForQuery(), nextPageToken.getOffset());
		}

		return new ListTextAnalyzersResponse()
			.setResults(page)
			.setNextPageToken(nextPageToken.getNextPageTokenForCurrentResults(page));
	}

	/**
	 * Parse the analyzer's opaque-JSON settings, require the canonical
	 * {@code analyzer.default} entry that field mappings bind to, enforce that the inner
	 * {@code analyzer} map contains only {@code default} and (optionally)
	 * {@code default_search}, collect every {@code $ref} qname inside, verify the qname
	 * format, and verify each ref resolves to an existing SynonymSet. AOSS validates
	 * everything else (component types, parameters, chain ordering) at index-build time.
	 *
	 * <p>The single-analyzer-per-record contract is enforced here so that one TextAnalyzer
	 * record always maps to one externally-addressable analyzer. Curators who need
	 * additional analyzers (a separate {@code headline}, {@code body}, etc.) create
	 * additional TextAnalyzer records, which are themselves shareable across
	 * SearchConfigurations.</p>
	 */
	private void validateSettings(String settingsJson) {
		JsonNode root = SearchAnalyzerJson.parse(settingsJson);
		// SearchConfiguration.defaultAnalyzer (and ColumnAnalyzerOverride) bind to a
		// TextAnalyzer by its bare qualified name; the index-build code resolves that to
		// the analyzer entry named "default". An analyzer that doesn't declare `default`
		// would build fine inside AOSS but would never be reachable from a
		// SearchConfiguration.
		JsonNode analyzerMap = root.get("analyzer");
		if (analyzerMap == null || !analyzerMap.isObject()) {
			throw new IllegalArgumentException(
					"settings must declare an analyzer named 'default' under analyzer.default.");
		}
		JsonNode defaultAnalyzer = analyzerMap.get(SearchAnalyzerJson.DEFAULT_ANALYZER_KEY);
		if (defaultAnalyzer == null || !defaultAnalyzer.isObject()) {
			throw new IllegalArgumentException(
					"settings must declare an analyzer named 'default' under analyzer.default.");
		}
		// Enforce one-record-one-analyzer: only `default` and (optionally) `default_search`
		// may appear inside the inner `analyzer` map. Any other key would be registered
		// into AOSS but unreachable from a binding (SearchConfiguration / ColumnAnalyzerOverride
		// always resolve to the bare `default`), so reject it here rather than letting it
		// rot.
		Set<String> rejected = new LinkedHashSet<>();
		Iterator<String> fieldNames = analyzerMap.fieldNames();
		while (fieldNames.hasNext()) {
			String key = fieldNames.next();
			if (!SearchAnalyzerJson.DEFAULT_ANALYZER_KEY.equals(key)
					&& !SearchAnalyzerJson.DEFAULT_SEARCH_ANALYZER_KEY.equals(key)) {
				rejected.add(key);
			}
		}
		if (!rejected.isEmpty()) {
			throw new IllegalArgumentException(
					"settings.analyzer must declare only '"
							+ SearchAnalyzerJson.DEFAULT_ANALYZER_KEY
							+ "' (and optionally '"
							+ SearchAnalyzerJson.DEFAULT_SEARCH_ANALYZER_KEY
							+ "'); rejected: " + rejected);
		}
		Set<String> refs = SearchAnalyzerJson.collectRefs(root);
		List<String> refList = new ArrayList<>(refs);
		for (String qname : refList) {
			SearchResourceConstants.validateQualifiedNameFormat(qname, "$ref");
		}
		if (!refList.isEmpty()) {
			List<String> missing = synonymSetDao.findNonExistentNames(refList);
			if (!missing.isEmpty()) {
				throw new IllegalArgumentException(
						"The following $ref synonym set name(s) do not exist: " + missing);
			}
		}

		// Resolve $refs against the SynonymSet store, then submit to AOSS _analyze for the
		// real component-shape / chain-ordering check. Curators get a synchronous wire-side
		// rejection at create/update time instead of an async FAILED state on the first
		// SearchIndex build that happens to use this analyzer.
		JsonNode resolvedRoot = SearchAnalyzerJson.resolveRefs(root, qname -> {
			Map<String, SynonymSet> map = synonymSetDao.getByQualifiedNames(
					Collections.singletonList(qname));
			SynonymSet ss = map.get(qname);
			return ss == null ? null : SearchAnalyzerJson.parse(ss.getDefinition());
		});
		openSearchManager.validateAnalyzerSettings(resolvedRoot);
	}

	private String resolveOrganizationId(String organizationName) {
		return organizationDao.getOrganizationByName(organizationName).getId();
	}
}
