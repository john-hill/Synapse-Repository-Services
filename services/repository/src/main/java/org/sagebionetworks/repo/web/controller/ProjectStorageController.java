package org.sagebionetworks.repo.web.controller;

import static org.sagebionetworks.repo.model.oauth.OAuthScope.modify;
import static org.sagebionetworks.repo.model.oauth.OAuthScope.view;

import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.limits.ProjectStorageLocationLimit;
import org.sagebionetworks.repo.model.limits.ProjectStorageUsage;
import org.sagebionetworks.repo.service.limits.ProjectStorageService;
import org.sagebionetworks.repo.web.RequiredScope;
import org.sagebionetworks.repo.web.UrlHelpers;
import org.sagebionetworks.repo.web.rest.doc.ControllerInfo;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Services to manage project storage usage and limits.
 */
@Controller
@RequestMapping(UrlHelpers.REPO_PATH)
@ControllerInfo(displayName = "Project Storage Services", path = "repo/v1")
public class ProjectStorageController {

	private ProjectStorageService service;

	public ProjectStorageController(ProjectStorageService service) {
		this.service = service;
	}

	/**
	 * Get the current project usage and limits for the project with the given id.
	 * 
	 * @param userId
	 * @param projectId
	 * @return
	 */
	@RequiredScope({ view })
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = { UrlHelpers.PROJECT_STORAGE_USAGE }, method = RequestMethod.GET)
	public ProjectStorageUsage getProjectStorageUsage(@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId, @PathVariable("projectId") String projectId) {
		return service.getProjectStorageUsage(userId, projectId);
	}

	/**
	 * Allows to set a limit on a project for the storage location provided in the
	 * <a href= "${org.sagebionetworks.repo.model.limits.ProjectStorageLocationLimit}">request body</a>. If the
	 * <a href= "${org.sagebionetworks.repo.model.limits.ProjectStorageLocationLimit}">maxAllowedFileBytes</a> property in
	 * the request is null, no limit will be applied to the storage location. Only members of the synapse plan managers team
	 * can perform this operation.
	 * 
	 * @param userId
	 * @param projectId
	 * @param limit
	 * @return
	 */
	@RequiredScope({ modify })
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = { UrlHelpers.PROJECT_STORAGE_LIMIT }, method = RequestMethod.PUT)
	public ProjectStorageLocationLimit setProjectStorageLocationLimit(@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
		@PathVariable("projectId") String projectId, @RequestBody ProjectStorageLocationLimit request) {

		if (!projectId.equals(request.getProjectId())) {
			throw new IllegalArgumentException("The request projectId must match the id in the path.");
		}

		return service.setProjectStorageLocationLimit(userId, request);

	}

}
