package org.sagebionetworks.repo.web.service;

import java.util.List;

import javax.servlet.http.HttpServletRequest;

import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.model.DatastoreException;
import org.sagebionetworks.repo.model.PaginatedResults;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.Principal;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.web.NotFoundException;
import org.sagebionetworks.repo.web.UrlHelpers;
import org.springframework.beans.factory.annotation.Autowired;

public class UserGroupServiceImpl implements UserGroupService {
	
	@Autowired
	UserManager userManager;
	
	@Override
	public PaginatedResults<Principal> getUserGroups(HttpServletRequest request,
			Long userId, long offset, long limit) 
			throws DatastoreException, UnauthorizedException, NotFoundException {
		UserInfo userInfo = userManager.getUserInfo(userId);
		List<Principal> results = userManager.getAllPrincipals(userInfo, limit, offset);
		long totalNumberOfResults = userManager.getPrincipalCount();
		return new PaginatedResults<Principal>(
				request.getServletPath()+UrlHelpers.USERGROUP, 
				results,
				totalNumberOfResults, 
				offset, 
				limit,
				"", 
				true);
	}
}
