package org.sagebionetworks.repo.manager.search;

import java.util.ArrayList;
import java.util.List;

import org.sagebionetworks.repo.model.ACCESS_TYPE;
import org.sagebionetworks.repo.model.AccessControlListDAO;
import org.sagebionetworks.repo.model.AuthorizationUtils;
import org.sagebionetworks.repo.model.NextPageToken;
import org.sagebionetworks.repo.model.ObjectType;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dbo.schema.OrganizationDao;
import org.sagebionetworks.repo.model.dbo.search.ColumnAnalyzerOverrideDao;
import org.sagebionetworks.repo.model.dbo.search.TextAnalyzerDao;
import org.sagebionetworks.repo.model.search.table.ColumnAnalyzerOverride;
import org.sagebionetworks.repo.model.search.table.ColumnAnalyzerOverrideEntry;
import org.sagebionetworks.repo.model.search.table.ListColumnAnalyzerOverridesRequest;
import org.sagebionetworks.repo.model.search.table.ListColumnAnalyzerOverridesResponse;
import org.sagebionetworks.repo.transactions.WriteTransaction;
import org.sagebionetworks.repo.web.NotFoundException;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.stereotype.Service;

@Service
public class ColumnAnalyzerOverrideManagerImpl implements ColumnAnalyzerOverrideManager {

	private static final String MSG_UNAUTHORIZED = "Only Sage Bionetworks employees can manage column analyzer overrides.";

	private final ColumnAnalyzerOverrideDao columnAnalyzerOverrideDao;
	private final TextAnalyzerDao textAnalyzerDao;
	private final AccessControlListDAO aclDao;
	private final OrganizationDao organizationDao;

	public ColumnAnalyzerOverrideManagerImpl(ColumnAnalyzerOverrideDao columnAnalyzerOverrideDao,
			TextAnalyzerDao textAnalyzerDao, AccessControlListDAO aclDao, OrganizationDao organizationDao) {
		this.columnAnalyzerOverrideDao = columnAnalyzerOverrideDao;
		this.textAnalyzerDao = textAnalyzerDao;
		this.aclDao = aclDao;
		this.organizationDao = organizationDao;
	}

	@Override
	@WriteTransaction
	public ColumnAnalyzerOverride create(UserInfo user, ColumnAnalyzerOverride request) {
		ValidateArgument.required(user, "user");
		ValidateArgument.required(request, "request");
		ValidateArgument.requiredNotBlank(request.getOrganizationName(), "organizationName");
		ValidateArgument.requiredNotBlank(request.getName(), "name");
		SearchResourceConstants.validateResourceName(request.getName());

		AuthorizationUtils.disallowAnonymous(user);
		if (!AuthorizationUtils.isSageEmployeeOrAdmin(user)) {
			throw new UnauthorizedException(MSG_UNAUTHORIZED);
		}
		if (!user.isAdmin()) {
			aclDao.canAccess(user, resolveOrganizationId(request.getOrganizationName()), ObjectType.ORGANIZATION, ACCESS_TYPE.CREATE)
				.checkAuthorizationOrElseThrow();
		}

		validateEntryAnalyzerNames(request);

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
		ValidateArgument.requiredNotBlank(request.getOrganizationName(), "organizationName");
		ValidateArgument.requiredNotBlank(request.getName(), "name");
		SearchResourceConstants.validateResourceName(request.getName());

		AuthorizationUtils.disallowAnonymous(user);
		if (!AuthorizationUtils.isSageEmployeeOrAdmin(user)) {
			throw new UnauthorizedException(MSG_UNAUTHORIZED);
		}
		ColumnAnalyzerOverride existing = columnAnalyzerOverrideDao.get(request.getId())
			.orElseThrow(() -> new NotFoundException("A column analyzer override with the given id does not exist."));

		if (!existing.getOrganizationName().equals(request.getOrganizationName())) {
			throw new IllegalArgumentException(SearchResourceConstants.ORG_NAME_IMMUTABLE_MSG);
		}
		if (!existing.getName().equals(request.getName())) {
			throw new IllegalArgumentException(SearchResourceConstants.NAME_IMMUTABLE_MSG);
		}

		if (!user.isAdmin()) {
			aclDao.canAccess(user, resolveOrganizationId(existing.getOrganizationName()), ObjectType.ORGANIZATION, ACCESS_TYPE.UPDATE)
				.checkAuthorizationOrElseThrow();
		}

		validateEntryAnalyzerNames(request);

		return columnAnalyzerOverrideDao.update(user.getId(), request);
	}

	@Override
	@WriteTransaction
	public void delete(UserInfo user, String id) {
		ValidateArgument.required(user, "user");
		ValidateArgument.requiredNotBlank(id, "id");

		AuthorizationUtils.disallowAnonymous(user);
		if (!AuthorizationUtils.isSageEmployeeOrAdmin(user)) {
			throw new UnauthorizedException(MSG_UNAUTHORIZED);
		}

		ColumnAnalyzerOverride existing = columnAnalyzerOverrideDao.get(id)
			.orElseThrow(() -> new NotFoundException("A column analyzer override with the given id does not exist."));

		if (!user.isAdmin()) {
			aclDao.canAccess(user, resolveOrganizationId(existing.getOrganizationName()), ObjectType.ORGANIZATION, ACCESS_TYPE.DELETE)
				.checkAuthorizationOrElseThrow();
		}

		columnAnalyzerOverrideDao.delete(id);
	}

	@Override
	public ListColumnAnalyzerOverridesResponse list(UserInfo user, ListColumnAnalyzerOverridesRequest request) {
		ValidateArgument.required(request, "request");

		NextPageToken nextPageToken = new NextPageToken(request.getNextPageToken());

		List<ColumnAnalyzerOverride> page;
		if (request.getOrganizationName() == null) {
			page = columnAnalyzerOverrideDao.listAll(nextPageToken.getLimitForQuery(), nextPageToken.getOffset());
		} else {
			page = columnAnalyzerOverrideDao.list(request.getOrganizationName(),
				nextPageToken.getLimitForQuery(), nextPageToken.getOffset());
		}

		return new ListColumnAnalyzerOverridesResponse()
			.setResults(page)
			.setNextPageToken(nextPageToken.getNextPageTokenForCurrentResults(page));
	}

	private void validateEntryAnalyzerNames(ColumnAnalyzerOverride override) {
		if (override.getOverrides() == null || override.getOverrides().isEmpty()) {
			return;
		}
		List<String> qualifiedNames = new ArrayList<>();
		for (ColumnAnalyzerOverrideEntry entry : override.getOverrides()) {
			if (entry.getAnalyzer() != null) {
				SearchResourceConstants.validateQualifiedNameFormat(entry.getAnalyzer(), "analyzer");
				qualifiedNames.add(entry.getAnalyzer());
			}
		}
		if (!qualifiedNames.isEmpty()) {
			List<String> missing = textAnalyzerDao.findNonExistentNames(qualifiedNames);
			if (!missing.isEmpty()) {
				throw new IllegalArgumentException("The following text analyzer names do not exist: " + missing);
			}
		}
	}


	private String resolveOrganizationId(String organizationName) {
		return organizationDao.getOrganizationByName(organizationName).getId();
	}
}
