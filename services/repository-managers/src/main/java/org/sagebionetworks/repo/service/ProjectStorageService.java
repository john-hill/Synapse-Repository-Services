package org.sagebionetworks.repo.service;

import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.manager.limits.ProjectStorageLimitManager;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.limits.ProjectStorageLocationLimit;
import org.springframework.stereotype.Service;

@Service
public class ProjectStorageService {

	private UserManager userManager;
	
	private ProjectStorageLimitManager storageLimitsManager;
	
	public ProjectStorageService(UserManager userManager, ProjectStorageLimitManager storageLimitsManager) {
		this.userManager = userManager;
		this.storageLimitsManager = storageLimitsManager;
	}
	
	public ProjectStorageLocationLimit setProjectStorageLocationLimit(Long userId, ProjectStorageLocationLimit limit) {
		UserInfo userInfo = userManager.getUserInfo(userId);
		
		return storageLimitsManager.setProjectStorageLimit(userInfo, limit);
	}

}
