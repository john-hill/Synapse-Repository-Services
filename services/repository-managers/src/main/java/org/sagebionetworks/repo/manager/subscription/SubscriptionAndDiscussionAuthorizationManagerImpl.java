package org.sagebionetworks.repo.manager.subscription;

import org.sagebionetworks.repo.manager.dataaccess.DataAccessAuthorizationManager;
import org.sagebionetworks.repo.manager.entity.EntityAuthorizationManager;
import org.sagebionetworks.repo.model.ACCESS_TYPE;
import org.sagebionetworks.repo.model.AuthorizationUtils;
import org.sagebionetworks.repo.model.DatastoreException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.auth.AuthorizationStatus;
import org.sagebionetworks.repo.model.dbo.dao.dataaccess.SubmissionDAO;
import org.sagebionetworks.repo.model.dbo.dao.discussion.DiscussionThreadDAO;
import org.sagebionetworks.repo.model.dbo.dao.discussion.ForumDAO;
import org.sagebionetworks.repo.model.discussion.DiscussionFilter;
import org.sagebionetworks.repo.model.discussion.DiscussionThreadBundle;
import org.sagebionetworks.repo.model.discussion.Forum;
import org.sagebionetworks.repo.model.discussion.ForumObjectType;
import org.sagebionetworks.repo.model.subscription.SubscriptionObjectType;
import org.sagebionetworks.repo.web.NotFoundException;
import org.springframework.stereotype.Service;

@Service
public class SubscriptionAndDiscussionAuthorizationManagerImpl implements SubscriptionAndDiscussionAuthorizationManager {

    private final EntityAuthorizationManager entityAuthorizationManager;
    private final ForumDAO forumDao;
    private final DiscussionThreadDAO threadDao;
    private final SubmissionDAO dataAccessSubmissionDao;
    private final DataAccessAuthorizationManager dataAccessAuthorizationManager;

    public SubscriptionAndDiscussionAuthorizationManagerImpl(EntityAuthorizationManager entityAuthorizationManager, ForumDAO forumDao,
                                                             DiscussionThreadDAO threadDao, SubmissionDAO dataAccessSubmissionDao,
                                                             DataAccessAuthorizationManager dataAccessAuthorizationManager) {
        this.entityAuthorizationManager = entityAuthorizationManager;
        this.forumDao = forumDao;
        this.threadDao = threadDao;
        this.dataAccessSubmissionDao = dataAccessSubmissionDao;
        this.dataAccessAuthorizationManager = dataAccessAuthorizationManager;
    }

    @Override
    public AuthorizationStatus canSubscribe(UserInfo userInfo, String objectId, SubscriptionObjectType objectType)
            throws DatastoreException, NotFoundException {
        if (userInfo.isUserAnonymous()) {
            return AuthorizationStatus.accessDenied("Anonymous cannot subscribe.");
        }
        switch (objectType) {
            case FORUM:
                Forum forum = forumDao.getForum(Long.parseLong(objectId));
                return canAccessObjectType(userInfo, forum.getObjectType(), forum.getObjectId(), ACCESS_TYPE.READ);
            case THREAD:
                DiscussionThreadBundle thread = threadDao.getThread(Long.parseLong(objectId), DiscussionFilter.NO_FILTER);
                return canAccessObjectType(userInfo, thread.getObjectType(), thread.getObjectId(), ACCESS_TYPE.READ);
            case DATA_ACCESS_SUBMISSION:
                if (AuthorizationUtils.isACTTeamMemberOrAdmin(userInfo)) {
                    return AuthorizationStatus.authorized();
                } else {
                    return AuthorizationStatus.accessDenied("Only ACT member can follow this topic.");
                }
            case DATA_ACCESS_SUBMISSION_STATUS:
                if (dataAccessSubmissionDao.isAccessor(objectId, userInfo.getId().toString())) {
                    return AuthorizationStatus.authorized();
                } else {
                    return AuthorizationStatus.accessDenied("Only accessors can follow this topic.");
                }
        }
        return AuthorizationStatus.accessDenied("The objectType is unsubscribable.");
    }

    @Override
    public AuthorizationStatus canAccessObjectType(UserInfo userInfo, ForumObjectType objectType, String objectId, ACCESS_TYPE accessType) {
        switch (objectType) {
            case ENTITY:
                return entityAuthorizationManager.hasAccess(userInfo, objectId, accessType);
            case ACCESS_REQUIREMENT:
                return dataAccessAuthorizationManager.canReviewAccessRequirementSubmissions(userInfo, objectId);
            default:
                throw new IllegalArgumentException("Unsupported forum object type: " + objectType);
        }
    }
}
