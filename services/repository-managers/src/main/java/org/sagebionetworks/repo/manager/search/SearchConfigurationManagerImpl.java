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

	private final SearchConfigurationDao searchConfigurationDao;
	private final AccessControlListDAO aclDao;
	private final OrganizationDao organizationDao;
	private final SynonymSetDao synonymSetDao;
	private final ColumnAnalyzerOverrideDao columnAnalyzerOverrideDao;
	private final NodeDAO nodeDAO;

	public SearchConfigurationManagerImpl(SearchConfigurationDao searchConfigurationDao, AccessControlListDAO aclDao,
			OrganizationDao organizationDao, SynonymSetDao synonymSetDao,
			ColumnAnalyzerOverrideDao columnAnalyzerOverrideDao, NodeDAO nodeDAO) {
		this.searchConfigurationDao = searchConfigurationDao;
		this.aclDao = aclDao;
		this.organizationDao = organizationDao;
		this.synonymSetDao = synonymSetDao;
		this.columnAnalyzerOverrideDao = columnAnalyzerOverrideDao;
		this.nodeDAO = nodeDAO;
	}

	@Override
	@WriteTransaction
	public SearchConfiguration create(UserInfo user, SearchConfiguration request) {
		ValidateArgument.required(user, "user");
		ValidateArgument.required(request, "request");
		ValidateArgument.requiredNotBlank(request.getOrganizationName(), "organizationName");
		ValidateArgument.requiredNotBlank(request.getName(), "name");

		AuthorizationUtils.disallowAnonymous(user);
		if (!AuthorizationUtils.isSageEmployeeOrAdmin(user)) {
			throw new UnauthorizedException("Only Sage Bionetworks employees can manage search configurations.");
		}
		if (!user.isAdmin()) {
			aclDao.canAccess(user, resolveOrganizationId(request.getOrganizationName()), ObjectType.ORGANIZATION, ACCESS_TYPE.CREATE)
				.checkAuthorizationOrElseThrow();
		}

		validateReferencedIds(request);

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

		AuthorizationUtils.disallowAnonymous(user);
		if (!AuthorizationUtils.isSageEmployeeOrAdmin(user)) {
			throw new UnauthorizedException("Only Sage Bionetworks employees can manage search configurations.");
		}
		SearchConfiguration existing = searchConfigurationDao.get(request.getId())
			.orElseThrow(() -> new NotFoundException("A search configuration with the given id does not exist."));

		if (!existing.getOrganizationName().equals(request.getOrganizationName())) {
			throw new IllegalArgumentException("The organizationName cannot be changed.");
		}

		if (!user.isAdmin()) {
			aclDao.canAccess(user, resolveOrganizationId(existing.getOrganizationName()), ObjectType.ORGANIZATION, ACCESS_TYPE.UPDATE)
				.checkAuthorizationOrElseThrow();
		}

		validateReferencedIds(request);

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

	private void validateReferencedIds(SearchConfiguration config) {
		if (config.getSynonymSetIds() != null && !config.getSynonymSetIds().isEmpty()) {
			List<String> missing = synonymSetDao.findNonExistentIds(config.getSynonymSetIds());
			if (!missing.isEmpty()) {
				throw new IllegalArgumentException("The following synonym set IDs do not exist: " + missing);
			}
		}
		if (config.getColumnAnalyzerOverrideIds() != null && !config.getColumnAnalyzerOverrideIds().isEmpty()) {
			List<String> missing = columnAnalyzerOverrideDao.findNonExistentIds(config.getColumnAnalyzerOverrideIds());
			if (!missing.isEmpty()) {
				throw new IllegalArgumentException("The following column analyzer override IDs do not exist: " + missing);
			}
		}
	}

	private String resolveOrganizationId(String organizationName) {
		return organizationDao.getOrganizationByName(organizationName).getId();
	}
}
