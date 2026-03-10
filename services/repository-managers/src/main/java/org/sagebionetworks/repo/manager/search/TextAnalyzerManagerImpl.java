package org.sagebionetworks.repo.manager.search;

import java.util.List;

import org.sagebionetworks.repo.model.ACCESS_TYPE;
import org.sagebionetworks.repo.model.AccessControlListDAO;
import org.sagebionetworks.repo.model.AuthorizationUtils;
import org.sagebionetworks.repo.model.NextPageToken;
import org.sagebionetworks.repo.model.ObjectType;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dbo.search.TextAnalyzerDao;
import org.sagebionetworks.repo.model.table.search.ListTextAnalyzersRequest;
import org.sagebionetworks.repo.model.table.search.ListTextAnalyzersResponse;
import org.sagebionetworks.repo.model.table.search.TextAnalyzer;
import org.sagebionetworks.repo.transactions.WriteTransaction;
import org.sagebionetworks.repo.web.NotFoundException;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class TextAnalyzerManagerImpl implements TextAnalyzerManager {

	private static final String MSG_UNAUTHORIZED = "Only Sage Bionetworks employees can manage text analyzers.";

	private final TextAnalyzerDao textAnalyzerDao;
	private final AccessControlListDAO aclDao;

	public TextAnalyzerManagerImpl(TextAnalyzerDao textAnalyzerDao, AccessControlListDAO aclDao) {
		this.textAnalyzerDao = textAnalyzerDao;
		this.aclDao = aclDao;
	}

	@Override
	@WriteTransaction
	public TextAnalyzer create(UserInfo user, TextAnalyzer analyzer) {
		ValidateArgument.required(user, "user");
		ValidateArgument.required(analyzer, "analyzer");
		ValidateArgument.requiredNotBlank(analyzer.getOrganizationId(), "organizationId");
		ValidateArgument.requiredNotBlank(analyzer.getName(), "name");
		ValidateArgument.required(analyzer.getSettings(), "settings");

		AuthorizationUtils.disallowAnonymous(user);
		if (!AuthorizationUtils.isSageEmployeeOrAdmin(user)) {
			throw new UnauthorizedException(MSG_UNAUTHORIZED);
		}
		if (!user.isAdmin()) {
			aclDao.canAccess(user, analyzer.getOrganizationId(), ObjectType.ORGANIZATION, ACCESS_TYPE.CREATE)
				.checkAuthorizationOrElseThrow();
		}

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
		ValidateArgument.requiredNotBlank(analyzer.getOrganizationId(), "organizationId");
		ValidateArgument.requiredNotBlank(analyzer.getName(), "name");

		AuthorizationUtils.disallowAnonymous(user);
		if (!AuthorizationUtils.isSageEmployeeOrAdmin(user)) {
			throw new UnauthorizedException(MSG_UNAUTHORIZED);
		}
		if (!user.isAdmin()) {
			aclDao.canAccess(user, analyzer.getOrganizationId(), ObjectType.ORGANIZATION, ACCESS_TYPE.UPDATE)
				.checkAuthorizationOrElseThrow();
		}

		textAnalyzerDao.get(Long.parseLong(analyzer.getId()))
			.orElseThrow(() -> new NotFoundException("TextAnalyzer with id '" + analyzer.getId() + "' does not exist."));

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
			aclDao.canAccess(user, existing.getOrganizationId(), ObjectType.ORGANIZATION, ACCESS_TYPE.DELETE)
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
		if (request.getOrganizationId() == null) {
			page = textAnalyzerDao.listAll(nextPageToken.getLimitForQuery(), nextPageToken.getOffset());
		} else {
			page = textAnalyzerDao.listByOrganization(
					Long.parseLong(request.getOrganizationId()), nextPageToken.getLimitForQuery(), nextPageToken.getOffset());
		}

		return new ListTextAnalyzersResponse()
			.setResults(page)
			.setNextPageToken(nextPageToken.getNextPageTokenForCurrentResults(page));
	}
}
