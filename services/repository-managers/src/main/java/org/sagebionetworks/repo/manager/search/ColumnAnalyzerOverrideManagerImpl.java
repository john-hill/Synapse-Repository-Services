package org.sagebionetworks.repo.manager.search;

import java.util.List;
import java.util.stream.Collectors;

import org.sagebionetworks.repo.model.ACCESS_TYPE;
import org.sagebionetworks.repo.model.AccessControlListDAO;
import org.sagebionetworks.repo.model.AuthorizationUtils;
import org.sagebionetworks.repo.model.NextPageToken;
import org.sagebionetworks.repo.model.ObjectType;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dbo.search.ColumnAnalyzerOverrideDao;
import org.sagebionetworks.repo.model.dbo.search.SearchConfigurationDao;
import org.sagebionetworks.repo.model.table.search.ColumnAnalyzerOverride;
import org.sagebionetworks.repo.model.table.search.ListColumnAnalyzerOverridesRequest;
import org.sagebionetworks.repo.model.table.search.ListColumnAnalyzerOverridesResponse;
import org.sagebionetworks.repo.model.table.search.SearchConfiguration;
import org.sagebionetworks.repo.transactions.WriteTransaction;
import org.sagebionetworks.repo.web.NotFoundException;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.stereotype.Service;

@Service
public class ColumnAnalyzerOverrideManagerImpl implements ColumnAnalyzerOverrideManager {

	private final ColumnAnalyzerOverrideDao columnAnalyzerOverrideDao;
	private final SearchConfigurationDao searchConfigurationDao;
	private final AccessControlListDAO aclDao;

	public ColumnAnalyzerOverrideManagerImpl(ColumnAnalyzerOverrideDao columnAnalyzerOverrideDao,
			SearchConfigurationDao searchConfigurationDao, AccessControlListDAO aclDao) {
		this.columnAnalyzerOverrideDao = columnAnalyzerOverrideDao;
		this.searchConfigurationDao = searchConfigurationDao;
		this.aclDao = aclDao;
	}

	@Override
	@WriteTransaction
	public ColumnAnalyzerOverride create(UserInfo user, ColumnAnalyzerOverride request) {
		ValidateArgument.required(user, "user");
		ValidateArgument.required(request, "request");
		ValidateArgument.requiredNotBlank(request.getOrganizationId(), "organizationId");
		ValidateArgument.requiredNotBlank(request.getName(), "name");

		AuthorizationUtils.disallowAnonymous(user);
		if (!AuthorizationUtils.isSageEmployeeOrAdmin(user)) {
			throw new UnauthorizedException("Only Sage Bionetworks employees can manage search configurations.");
		}
		if (!user.isAdmin()) {
			aclDao.canAccess(user, request.getOrganizationId(), ObjectType.ORGANIZATION, ACCESS_TYPE.CREATE)
				.checkAuthorizationOrElseThrow();
		}

		return columnAnalyzerOverrideDao.create(user.getId(), request);
	}

	@Override
	public ColumnAnalyzerOverride get(UserInfo user, String id) {
		ValidateArgument.requiredNotBlank(id, "id");

		return columnAnalyzerOverrideDao.get(id)
			.orElseThrow(() -> new NotFoundException("A column analyzer override with the given id does not exist."));
	}

	@Override
	@WriteTransaction
	public ColumnAnalyzerOverride update(UserInfo user, ColumnAnalyzerOverride request) {
		ValidateArgument.required(user, "user");
		ValidateArgument.required(request, "request");
		ValidateArgument.requiredNotBlank(request.getId(), "id");
		ValidateArgument.requiredNotBlank(request.getOrganizationId(), "organizationId");
		ValidateArgument.requiredNotBlank(request.getName(), "name");

		AuthorizationUtils.disallowAnonymous(user);
		if (!AuthorizationUtils.isSageEmployeeOrAdmin(user)) {
			throw new UnauthorizedException("Only Sage Bionetworks employees can manage search configurations.");
		}
		if (!user.isAdmin()) {
			aclDao.canAccess(user, request.getOrganizationId(), ObjectType.ORGANIZATION, ACCESS_TYPE.UPDATE)
				.checkAuthorizationOrElseThrow();
		}

		// Ensure the column analyzer override exists
		columnAnalyzerOverrideDao.get(request.getId())
			.orElseThrow(() -> new NotFoundException("A column analyzer override with the given id does not exist."));

		return columnAnalyzerOverrideDao.update(user.getId(), request);
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

		ColumnAnalyzerOverride existing = columnAnalyzerOverrideDao.get(id)
			.orElseThrow(() -> new NotFoundException("A column analyzer override with the given id does not exist."));

		if (!user.isAdmin()) {
			aclDao.canAccess(user, existing.getOrganizationId(), ObjectType.ORGANIZATION, ACCESS_TYPE.DELETE)
				.checkAuthorizationOrElseThrow();
		}

		// Check for referencing search configurations
		List<SearchConfiguration> refs = searchConfigurationDao.findByColumnAnalyzerOverrideId(id);
		if (!refs.isEmpty()) {
			List<String> ids = refs.stream().map(SearchConfiguration::getId).collect(Collectors.toList());
			throw new IllegalArgumentException(
				"Cannot delete column analyzer override because it is referenced by search configurations: " + ids);
		}

		columnAnalyzerOverrideDao.delete(id);
	}

	@Override
	public ListColumnAnalyzerOverridesResponse list(UserInfo user, ListColumnAnalyzerOverridesRequest request) {
		ValidateArgument.required(request, "request");

		NextPageToken nextPageToken = new NextPageToken(request.getNextPageToken());

		List<ColumnAnalyzerOverride> page;
		if (request.getOrganizationId() == null) {
			page = columnAnalyzerOverrideDao.listAll(nextPageToken.getLimitForQuery(), nextPageToken.getOffset());
		} else {
			page = columnAnalyzerOverrideDao.list(request.getOrganizationId(),
				nextPageToken.getLimitForQuery(), nextPageToken.getOffset());
		}

		return new ListColumnAnalyzerOverridesResponse()
			.setResults(page)
			.setNextPageToken(nextPageToken.getNextPageTokenForCurrentResults(page));
	}
}
