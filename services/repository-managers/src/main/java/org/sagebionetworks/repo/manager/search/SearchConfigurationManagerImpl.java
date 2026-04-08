package org.sagebionetworks.repo.manager.search;

import java.util.List;

import org.sagebionetworks.repo.model.ACCESS_TYPE;
import org.sagebionetworks.repo.model.AccessControlListDAO;
import org.sagebionetworks.repo.model.AuthorizationUtils;
import org.sagebionetworks.repo.model.NextPageToken;
import org.sagebionetworks.repo.model.ObjectType;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.NodeDAO;
import org.sagebionetworks.repo.model.dbo.schema.OrganizationDao;
import org.sagebionetworks.repo.model.dbo.search.ColumnAnalyzerOverrideDao;
import org.sagebionetworks.repo.model.dbo.search.SearchConfigurationDao;
import org.sagebionetworks.repo.model.dbo.search.SynonymSetDao;
import org.sagebionetworks.repo.model.dbo.search.TextAnalyzerDao;
import org.sagebionetworks.repo.model.jdo.KeyFactory;
import org.sagebionetworks.repo.model.search.table.BindSearchConfigToEntityRequest;
import org.sagebionetworks.repo.model.search.table.ListSearchConfigurationsRequest;
import org.sagebionetworks.repo.model.search.table.ListSearchConfigurationsResponse;
import org.sagebionetworks.repo.model.search.table.SearchConfigBinding;
import org.sagebionetworks.repo.model.search.table.SearchConfiguration;
import org.sagebionetworks.repo.transactions.WriteTransaction;
import org.sagebionetworks.repo.web.NotFoundException;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.stereotype.Service;

@Service
public class SearchConfigurationManagerImpl implements SearchConfigurationManager {

	private static final String ENTITY_OBJECT_TYPE = "entity";
	private static final String RESOURCE_NAME_PATTERN = "^[a-zA-Z][a-zA-Z0-9_]*$";
	private static final String RESOURCE_NAME_PATTERN_MSG = "Resource name must start with a letter and contain only letters, digits, and underscores.";

	private final SearchConfigurationDao searchConfigurationDao;
	private final AccessControlListDAO aclDao;
	private final OrganizationDao organizationDao;
	private final SynonymSetDao synonymSetDao;
	private final ColumnAnalyzerOverrideDao columnAnalyzerOverrideDao;
	private final TextAnalyzerDao textAnalyzerDao;
	private final NodeDAO nodeDAO;

	public SearchConfigurationManagerImpl(SearchConfigurationDao searchConfigurationDao, AccessControlListDAO aclDao,
			OrganizationDao organizationDao, SynonymSetDao synonymSetDao,
			ColumnAnalyzerOverrideDao columnAnalyzerOverrideDao, TextAnalyzerDao textAnalyzerDao, NodeDAO nodeDAO) {
		this.searchConfigurationDao = searchConfigurationDao;
		this.aclDao = aclDao;
		this.organizationDao = organizationDao;
		this.synonymSetDao = synonymSetDao;
		this.columnAnalyzerOverrideDao = columnAnalyzerOverrideDao;
		this.textAnalyzerDao = textAnalyzerDao;
		this.nodeDAO = nodeDAO;
	}

	@Override
	@WriteTransaction
	public SearchConfiguration create(UserInfo user, SearchConfiguration request) {
		ValidateArgument.required(user, "user");
		ValidateArgument.required(request, "request");
		ValidateArgument.requiredNotBlank(request.getOrganizationName(), "organizationName");
		ValidateArgument.requiredNotBlank(request.getName(), "name");
		if (!request.getName().matches(RESOURCE_NAME_PATTERN)) {
			throw new IllegalArgumentException(RESOURCE_NAME_PATTERN_MSG);
		}

		AuthorizationUtils.disallowAnonymous(user);
		if (!AuthorizationUtils.isSageEmployeeOrAdmin(user)) {
			throw new UnauthorizedException("Only Sage Bionetworks employees can manage search configurations.");
		}
		if (!user.isAdmin()) {
			aclDao.canAccess(user, resolveOrganizationId(request.getOrganizationName()), ObjectType.ORGANIZATION, ACCESS_TYPE.CREATE)
				.checkAuthorizationOrElseThrow();
		}

		validateReferencedNames(request);

		return searchConfigurationDao.create(user.getId(), request);
	}

	@Override
	public SearchConfiguration get(UserInfo user, String id) {
		ValidateArgument.requiredNotBlank(id, "id");

		return searchConfigurationDao.get(id)
			.orElseThrow(() -> new NotFoundException("A search configuration with the given id does not exist."));
	}

	@Override
	@WriteTransaction
	public SearchConfiguration update(UserInfo user, SearchConfiguration request) {
		ValidateArgument.required(user, "user");
		ValidateArgument.required(request, "request");
		ValidateArgument.requiredNotBlank(request.getId(), "id");
		ValidateArgument.requiredNotBlank(request.getOrganizationName(), "organizationName");
		ValidateArgument.requiredNotBlank(request.getName(), "name");
		if (!request.getName().matches(RESOURCE_NAME_PATTERN)) {
			throw new IllegalArgumentException(RESOURCE_NAME_PATTERN_MSG);
		}

		AuthorizationUtils.disallowAnonymous(user);
		if (!AuthorizationUtils.isSageEmployeeOrAdmin(user)) {
			throw new UnauthorizedException("Only Sage Bionetworks employees can manage search configurations.");
		}
		SearchConfiguration existing = searchConfigurationDao.get(request.getId())
			.orElseThrow(() -> new NotFoundException("A search configuration with the given id does not exist."));

		if (!existing.getOrganizationName().equals(request.getOrganizationName())) {
			throw new IllegalArgumentException("The organizationName cannot be changed.");
		}
		if (!existing.getName().equals(request.getName())) {
			throw new IllegalArgumentException("The name cannot be changed. Create a new resource instead.");
		}

		if (!user.isAdmin()) {
			aclDao.canAccess(user, resolveOrganizationId(existing.getOrganizationName()), ObjectType.ORGANIZATION, ACCESS_TYPE.UPDATE)
				.checkAuthorizationOrElseThrow();
		}

		validateReferencedNames(request);

		return searchConfigurationDao.update(user.getId(), request);
	}

	@Override
	@WriteTransaction
	public SearchConfigBinding bindSearchConfigToEntity(UserInfo user, BindSearchConfigToEntityRequest request) {
		ValidateArgument.required(user, "user");
		ValidateArgument.required(request, "request");
		ValidateArgument.requiredNotBlank(request.getEntityId(), "entityId");
		ValidateArgument.requiredNotBlank(request.getSearchConfigurationId(), "searchConfigurationId");

		AuthorizationUtils.disallowAnonymous(user);
		if (!AuthorizationUtils.isSageEmployeeOrAdmin(user)) {
			throw new UnauthorizedException("Only Sage Bionetworks employees can bind search configurations.");
		}

		Long entityId = KeyFactory.stringToKey(request.getEntityId());
		Long searchConfigId = Long.parseLong(request.getSearchConfigurationId());

		// Verify entity exists and user has EDIT permission
		if (!user.isAdmin()) {
			aclDao.canAccess(user, String.valueOf(entityId), ObjectType.ENTITY, ACCESS_TYPE.UPDATE)
				.checkAuthorizationOrElseThrow();
		}

		// Verify search config exists
		searchConfigurationDao.get(request.getSearchConfigurationId())
			.orElseThrow(() -> new NotFoundException("A search configuration with the given id does not exist."));

		searchConfigurationDao.bindSearchConfigToObject(searchConfigId, entityId, ENTITY_OBJECT_TYPE, user.getId());

		return searchConfigurationDao.getSearchConfigBindingForObject(entityId, ENTITY_OBJECT_TYPE)
			.orElseThrow(() -> new IllegalStateException("Failed to bind search configuration to entity."));
	}

	@Override
	public SearchConfigBinding getSearchConfigBinding(UserInfo user, String entityId) {
		ValidateArgument.requiredNotBlank(entityId, "entityId");

		Long nodeId = KeyFactory.stringToKey(entityId);
		Long firstBoundEntityId = nodeDAO.getEntityIdOfFirstBoundSearchConfig(nodeId)
			.orElseThrow(() -> new NotFoundException(
					"No search configuration binding found for entity '" + entityId + "' or any of its ancestors."));

		return searchConfigurationDao.getSearchConfigBindingForObject(firstBoundEntityId, ENTITY_OBJECT_TYPE)
			.orElseThrow(() -> new IllegalStateException("Binding not found after hierarchy walk."));
	}

	@Override
	@WriteTransaction
	public void clearSearchConfigBinding(UserInfo user, String entityId) {
		ValidateArgument.required(user, "user");
		ValidateArgument.requiredNotBlank(entityId, "entityId");

		AuthorizationUtils.disallowAnonymous(user);
		if (!AuthorizationUtils.isSageEmployeeOrAdmin(user)) {
			throw new UnauthorizedException("Only Sage Bionetworks employees can clear search configuration bindings.");
		}

		Long nodeId = KeyFactory.stringToKey(entityId);

		if (!user.isAdmin()) {
			aclDao.canAccess(user, String.valueOf(nodeId), ObjectType.ENTITY, ACCESS_TYPE.UPDATE)
				.checkAuthorizationOrElseThrow();
		}

		searchConfigurationDao.clearSearchConfigBinding(nodeId, ENTITY_OBJECT_TYPE);
	}

	@Override
	public ListSearchConfigurationsResponse list(UserInfo user, ListSearchConfigurationsRequest request) {
		ValidateArgument.required(request, "request");

		NextPageToken nextPageToken = new NextPageToken(request.getNextPageToken());

		List<SearchConfiguration> page;
		if (request.getOrganizationName() == null) {
			page = searchConfigurationDao.listAll(nextPageToken.getLimitForQuery(), nextPageToken.getOffset());
		} else {
			page = searchConfigurationDao.list(request.getOrganizationName(),
				nextPageToken.getLimitForQuery(), nextPageToken.getOffset());
		}

		return new ListSearchConfigurationsResponse()
			.setResults(page)
			.setNextPageToken(nextPageToken.getNextPageTokenForCurrentResults(page));
	}

	private void validateReferencedNames(SearchConfiguration config) {
		if (config.getDefaultAnalyzer() != null) {
			validateQualifiedNameFormat(config.getDefaultAnalyzer(), "defaultAnalyzer");
			List<String> missing = textAnalyzerDao.findNonExistentNames(List.of(config.getDefaultAnalyzer()));
			if (!missing.isEmpty()) {
				throw new IllegalArgumentException("The following default analyzer name does not exist: " + missing);
			}
		}
		if (config.getSynonymSets() != null && !config.getSynonymSets().isEmpty()) {
			for (String name : config.getSynonymSets()) {
				validateQualifiedNameFormat(name, "synonymSets");
			}
			List<String> missing = synonymSetDao.findNonExistentNames(config.getSynonymSets());
			if (!missing.isEmpty()) {
				throw new IllegalArgumentException("The following synonym set names do not exist: " + missing);
			}
		}
		if (config.getColumnAnalyzerOverrides() != null && !config.getColumnAnalyzerOverrides().isEmpty()) {
			for (String name : config.getColumnAnalyzerOverrides()) {
				validateQualifiedNameFormat(name, "columnAnalyzerOverrides");
			}
			List<String> missing = columnAnalyzerOverrideDao.findNonExistentNames(config.getColumnAnalyzerOverrides());
			if (!missing.isEmpty()) {
				throw new IllegalArgumentException("The following column analyzer override names do not exist: " + missing);
			}
		}
	}

	private static void validateQualifiedNameFormat(String qualifiedName, String fieldName) {
		if (qualifiedName.indexOf('-') < 1) {
			throw new IllegalArgumentException(
				"'" + fieldName + "' must be in '{organizationName}-{name}' format but was: '" + qualifiedName + "'");
		}
	}

	private String resolveOrganizationId(String organizationName) {
		return organizationDao.getOrganizationByName(organizationName).getId();
	}
}
