package org.sagebionetworks.repo.web.controller;

import static org.sagebionetworks.repo.model.oauth.OAuthScope.modify;
import static org.sagebionetworks.repo.model.oauth.OAuthScope.view;

import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.search.table.ListSearchConfigurationsRequest;
import org.sagebionetworks.repo.model.search.table.ListSearchConfigurationsResponse;
import org.sagebionetworks.repo.model.search.table.SearchConfiguration;
import org.sagebionetworks.repo.service.search.SearchConfigurationService;
import org.sagebionetworks.repo.web.RequiredScope;
import org.sagebionetworks.repo.web.UrlHelpers;
import org.sagebionetworks.repo.web.rest.doc.ControllerInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Services for managing search configurations.
 */
@ControllerInfo(displayName = "Search Configuration Services", path = "repo/v1")
@Controller
@RequestMapping(UrlHelpers.REPO_PATH)
public class SearchConfigurationController {

	@Autowired
	private SearchConfigurationService searchConfigurationService;

	/**
	 * Create a new search configuration.
	 *
	 * @param userId
	 * @param request
	 * @return
	 */
	@RequiredScope({ view, modify })
	@ResponseStatus(HttpStatus.CREATED)
	@RequestMapping(value = UrlHelpers.SEARCH_CONFIGURATION, method = RequestMethod.POST)
	public @ResponseBody SearchConfiguration create(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@RequestBody SearchConfiguration request) {
		return searchConfigurationService.create(userId, request);
	}

	/**
	 * Get a search configuration by its ID.
	 *
	 * @param userId
	 * @param searchConfigurationId
	 * @return
	 */
	@RequiredScope({ view })
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = UrlHelpers.SEARCH_CONFIGURATION_ID, method = RequestMethod.GET)
	public @ResponseBody SearchConfiguration get(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@PathVariable String searchConfigurationId) {
		return searchConfigurationService.get(userId, searchConfigurationId);
	}

	/**
	 * Update a search configuration.
	 *
	 * @param userId
	 * @param searchConfigurationId
	 * @param request
	 * @return
	 */
	@RequiredScope({ view, modify })
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = UrlHelpers.SEARCH_CONFIGURATION_ID, method = RequestMethod.PUT)
	public @ResponseBody SearchConfiguration update(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@PathVariable String searchConfigurationId,
			@RequestBody SearchConfiguration request) {
		if (!searchConfigurationId.equals(request.getId())) {
			throw new IllegalArgumentException(
				"The path ID: " + searchConfigurationId + " does not match the request body's ID: " + request.getId());
		}
		return searchConfigurationService.update(userId, request);
	}

	/**
	 * Delete a search configuration.
	 *
	 * @param userId
	 * @param searchConfigurationId
	 */
	@RequiredScope({ view, modify })
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@RequestMapping(value = UrlHelpers.SEARCH_CONFIGURATION_ID, method = RequestMethod.DELETE)
	public void delete(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@PathVariable String searchConfigurationId) {
		searchConfigurationService.delete(userId, searchConfigurationId);
	}

	/**
	 * List search configurations.
	 *
	 * @param userId
	 * @param request
	 * @return
	 */
	@RequiredScope({ view })
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = UrlHelpers.SEARCH_CONFIGURATION_LIST, method = RequestMethod.POST)
	public @ResponseBody ListSearchConfigurationsResponse list(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@RequestBody ListSearchConfigurationsRequest request) {
		return searchConfigurationService.list(userId, request);
	}
}
