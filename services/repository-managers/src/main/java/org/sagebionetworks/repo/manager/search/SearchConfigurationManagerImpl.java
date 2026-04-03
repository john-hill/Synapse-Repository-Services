package org.sagebionetworks.repo.manager.search;

import java.util.List;

import org.sagebionetworks.repo.model.ACCESS_TYPE;
import org.sagebionetworks.repo.model.AccessControlListDAO;
import org.sagebionetworks.repo.model.AuthorizationUtils;
import org.sagebionetworks.repo.model.NextPageToken;
import org.sagebionetworks.repo.model.ObjectType;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dbo.schema.OrganizationDao;
import org.sagebionetworks.repo.model.dbo.search.SearchConfigurationDao;
import org.sagebionetworks.repo.model.search.table.ListSearchConfigurationsRequest;
import org.sagebionetworks.repo.model.search.table.ListSearchConfigurationsResponse;
import org.sagebionetworks.repo.model.search.table.SearchConfiguration;
import org.sagebionetworks.repo.transactions.WriteTransaction;
import org.sagebionetworks.repo.web.NotFoundException;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.stereotype.Service;

@Service
public class SearchConfigurationManagerImpl implements SearchConfigurationManager {

	private final SearchConfigurationDao searchConfigurationDao;
	private final AccessControlListDAO aclDao;
	private final OrganizationDao organizationDao;

	public SearchConfigurationManagerImpl(SearchConfigurationDao searchConfigurationDao, AccessControlListDAO aclDao,
			OrganizationDao organizationDao) {
		this.searchConfigurationDao = searchConfigurationDao;
		this.aclDao = aclDao;
		this.organizationDao = organizationDao;
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

		return searchConfigurationDao.update(user.getId(), request);
	}

	@Override
	@WriteTransaction
	public void delete(UserInfo user, String id) {
		ValidateArgument.required(user, "user");
		ValidateArgument.requiredNotBlank(id, "id");

		AuthorizationUtils.disallowAnonymous(user);
		if (!AuthorizationUtils.isSageEmployeeOrAdmin(user)) {
			throw new UnauthorizedException("Only Sage Bionetworks employees can manage search configurations.");
		}

		SearchConfiguration existing = searchConfigurationDao.get(id)
			.orElseThrow(() -> new NotFoundException("A search configuration with the given id does not exist."));

		if (!user.isAdmin()) {
			aclDao.canAccess(user, resolveOrganizationId(existing.getOrganizationName()), ObjectType.ORGANIZATION, ACCESS_TYPE.DELETE)
				.checkAuthorizationOrElseThrow();
		}

		searchConfigurationDao.delete(id);
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

	private String resolveOrganizationId(String organizationName) {
		return organizationDao.getOrganizationByName(organizationName).getId();
	}
}
