package org.sagebionetworks.repo.service.metadata;

import org.sagebionetworks.repo.manager.discussion.ForumManager;
import org.sagebionetworks.repo.manager.limits.ProjectStorageLimitsManager;
import org.sagebionetworks.repo.manager.subscription.SubscriptionManager;
import org.sagebionetworks.repo.model.Project;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.discussion.Forum;
import org.sagebionetworks.repo.model.subscription.SubscriptionObjectType;
import org.sagebionetworks.repo.model.subscription.Topic;
import org.springframework.stereotype.Service;

/**
 *
 */
@Service
public class ProjectMetadataProvider implements TypeSpecificMetadataProvider<Project>, TypeSpecificCreateProvider<Project> {

	private ForumManager forumManager;
	
	private SubscriptionManager subscriptionManager;
	
	private ProjectStorageLimitsManager storageLimitsManager;

	public ProjectMetadataProvider(ForumManager forumManager, SubscriptionManager subscriptionManager, ProjectStorageLimitsManager storageLimitsManager) {
		this.forumManager = forumManager;
		this.subscriptionManager = subscriptionManager;
		this.storageLimitsManager = storageLimitsManager;
	}
	
	@Override
	public void addTypeSpecificMetadata(Project entity, UserInfo user, EventType eventType) {
		if(entity == null) throw new IllegalArgumentException("Entity cannot be null");
		if(entity.getId() == null) throw new IllegalArgumentException("Entity.id cannot be null");
	}

	@Override
	public void entityCreated(UserInfo userInfo, Project project) {
		Forum forum = forumManager.createForum(userInfo, project.getId());
		Topic toSubscribe = new Topic();
		toSubscribe.setObjectId(forum.getId());
		toSubscribe.setObjectType(SubscriptionObjectType.FORUM);
		subscriptionManager.create(userInfo, toSubscribe);
		storageLimitsManager.setDefaultProjectStorageLimit(project.getId(), ProjectStorageLimitsManager.DEFAULT_STORAGE_LOCATION_ID);
	}
}
