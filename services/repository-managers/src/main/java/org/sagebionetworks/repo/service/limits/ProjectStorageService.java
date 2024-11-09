package org.sagebionetworks.repo.service.limits;

import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.manager.limits.ProjectStorageLimitsManager;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.limits.ProjectStorageLocationLimit;
import org.sagebionetworks.repo.model.limits.ProjectStorageUsage;
import org.springframework.stereotype.Service;

@Service
public class ProjectStorageService {

	private UserManager userManager;
	
	private ProjectStorageLimitsManager storageLimitsManager;
	
	public ProjectStorageService(UserManager userManager, ProjectStorageLimitsManager storageLimitsManager) {
		this.userManager = userManager;
		this.storageLimitsManager = storageLimitsManager;
	}
	
	public ProjectStorageUsage getProjectStorageUsage(Long userId, String projectId) {
		UserInfo userInfo = userManager.getUserInfo(userId);
		
		return storageLimitsManager.getProjectStorageUsage(userInfo, projectId);
	}
	
	public ProjectStorageLocationLimit setProjectStorageLocationLimit(Long userId, ProjectStorageLocationLimit limit) {
		UserInfo userInfo = userManager.getUserInfo(userId);
		
		return storageLimitsManager.setProjectStorageLimit(userInfo, limit);
	}

}
