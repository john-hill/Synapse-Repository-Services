package org.sagebionetworks.repo.manager.subscription;

import org.sagebionetworks.repo.model.ACCESS_TYPE;
import org.sagebionetworks.repo.model.DatastoreException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.auth.AuthorizationStatus;
import org.sagebionetworks.repo.model.discussion.ForumObjectType;
import org.sagebionetworks.repo.model.subscription.SubscriptionObjectType;
import org.sagebionetworks.repo.web.NotFoundException;

public interface SubscriptionAndDiscussionAuthorizationManager {

	/**
	 * Check if the user can subscribe to the given object.
	 *
	 * @param userInfo
	 * @param objectId
	 * @param objectType
	 * @return
	 */
	AuthorizationStatus canSubscribe(UserInfo userInfo, String objectId, SubscriptionObjectType objectType)
			throws DatastoreException, NotFoundException;

	/**
	 * Check if the user can access to the given forum object type.
	 *
	 * @param userInfo
	 * @param objectType
	 * @param objectId
	 * @param accessType
	 * @return
	 */
	AuthorizationStatus canAccessObjectType(UserInfo userInfo, ForumObjectType objectType, String objectId, ACCESS_TYPE accessType);
}
