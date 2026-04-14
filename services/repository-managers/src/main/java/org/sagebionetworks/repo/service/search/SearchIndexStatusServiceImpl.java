package org.sagebionetworks.repo.service.search;

import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.manager.EntityManager;
import org.sagebionetworks.repo.manager.entity.EntityAuthorizationManager;
import org.sagebionetworks.repo.model.ACCESS_TYPE;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.jdo.KeyFactory;
import org.sagebionetworks.repo.model.search.table.SearchIndex;
import org.sagebionetworks.repo.model.search.table.SearchIndexStatus;
import org.sagebionetworks.table.cluster.ConnectionFactory;
import org.sagebionetworks.table.cluster.search.SearchIndexStatusDao;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.stereotype.Service;

@Service
public class SearchIndexStatusServiceImpl implements SearchIndexStatusService {

	private final UserManager userManager;
	private final EntityManager entityManager;
	private final EntityAuthorizationManager entityAuthorizationManager;
	private final ConnectionFactory connectionFactory;

	public SearchIndexStatusServiceImpl(UserManager userManager, EntityManager entityManager,
			EntityAuthorizationManager entityAuthorizationManager, ConnectionFactory connectionFactory) {
		this.userManager = userManager;
		this.entityManager = entityManager;
		this.entityAuthorizationManager = entityAuthorizationManager;
		this.connectionFactory = connectionFactory;
	}

	@Override
	public SearchIndexStatus getSearchIndexStatus(Long userId, String searchIndexId) {
		ValidateArgument.required(userId, "userId");
		ValidateArgument.required(searchIndexId, "searchIndexId");

		UserInfo user = userManager.getUserInfo(userId);

		// Verify the entity exists and is a SearchIndex
		entityManager.getEntity(user, searchIndexId, SearchIndex.class);
		if (!user.isAdmin()) {
			entityAuthorizationManager.hasAccess(user, searchIndexId, ACCESS_TYPE.READ)
					.checkAuthorizationOrElseThrow();
		}

		SearchIndexStatusDao statusDao = connectionFactory.getSearchIndexStatusDao();
		return statusDao.getStatus(KeyFactory.stringToKey(searchIndexId))
				.orElseGet(() -> {
					SearchIndexStatus status = new SearchIndexStatus();
					status.setSearchIndexId(searchIndexId);
					return status;
				});
	}
}
