package org.sagebionetworks.repo.web.controller;

import static org.sagebionetworks.repo.model.oauth.OAuthScope.modify;
import static org.sagebionetworks.repo.model.oauth.OAuthScope.view;

import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.asynch.AsyncJobId;
import org.sagebionetworks.repo.model.grid.CreateGridRequest;
import org.sagebionetworks.repo.model.grid.CreateGridResponse;
import org.sagebionetworks.repo.model.grid.CreateReplicaRequest;
import org.sagebionetworks.repo.model.grid.CreateReplicaResponse;
import org.sagebionetworks.repo.model.grid.GridQueryRequest;
import org.sagebionetworks.repo.model.grid.GridQueryResponse;
import org.sagebionetworks.repo.model.grid.GridSession;
import org.sagebionetworks.repo.model.grid.UpdateGridRequest;
import org.sagebionetworks.repo.model.grid.UpdateGridResponse;
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
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Services for create and managing data curation sandbox grid.
 */
@Controller
@ControllerInfo(displayName = "Grid Services", path = "repo/v1")
@RequestMapping(UrlHelpers.REPO_PATH)
public class GridController {

	/**
	 * Start a job to create a new curation grid session given a CSV and data model.
	 * 
	 * @param userId
	 * @param request
	 * @return
	 */
	@RequiredScope({ view, modify })
	@ResponseStatus(HttpStatus.CREATED)
	@RequestMapping(value = UrlHelpers.GRID_SESSION_ASYNC_START, method = RequestMethod.POST)
	public @ResponseBody AsyncJobId createGrid(@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@RequestBody CreateGridRequest request) {

		return null;
	}

	/**
	 * Get the resulting grid session started by:
	 * <a href="POST.grid.session.async.start">POST /grid/session/async/start</a>
	 * </p>
	 * Only the user that started the job may get the job's results.
	 * 
	 * @param userId
	 * @param asyncToken
	 * @return
	 * @throws Throwable
	 */
	@RequiredScope({ view })
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = UrlHelpers.GRID_SESSION_ASYNC_GET, method = RequestMethod.GET)
	public @ResponseBody CreateGridResponse getCreatedGrid(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId, @PathVariable String asyncToken)
			throws Throwable {
		return null;
	}

	/**
	 * Get the basic information about an existing grid session.
	 * 
	 * @param userId
	 * @param sessionId
	 * @return
	 * @throws Throwable
	 */
	@RequiredScope({ view })
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = UrlHelpers.GRID_SESSION_ID, method = RequestMethod.GET)
	public @ResponseBody GridSession getGridSession(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId, @PathVariable String sessionId)
			throws Throwable {
		return null;
	}

	/**
	 * A return_control agent function to run a query against the grid. Context
	 * Protocol (MCP).
	 * 
	 * @param userId
	 * @param sessionId The grid session ID.
	 * @return
	 * @throws Throwable
	 */
	@RequiredScope({ view })
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = UrlHelpers.GRID_SESSION_ID_SQL_QUERY, method = RequestMethod.POST)
	public @ResponseBody GridQueryResponse queryGrid(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId, @PathVariable String sessionId,
			@RequestBody GridQueryRequest request) throws Throwable {
		return null;
	}

	/**
	 * A return_control agent function to support agent update request.
	 * 
	 * @param userId
	 * @param sessionId The grid session ID.
	 * @return
	 * @throws Throwable
	 */
	@RequiredScope({ view, modify })
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = UrlHelpers.GRID_SESSION_ID_SQL_UPDATE, method = RequestMethod.PUT)
	public @ResponseBody UpdateGridResponse updateGrid(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId, @PathVariable String sessionId,
			@RequestBody UpdateGridRequest request) {

		return null;
	}

	/**
	 * Get the JSON Schema that defines the validation rules for the given grid
	 * session. Agents use this method to load the JSON schema for this grid into
	 * their context window.
	 * 
	 * @param userId
	 * @param sessionId
	 * @return The JSON Schema that defines the validation rules of this grid.
	 * @throws Throwable
	 */
	@RequiredScope({ view })
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = UrlHelpers.GRID_SESSION_ID_SCHEMA, method = RequestMethod.GET)
	public @ResponseBody String getGridSchema(@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@PathVariable String sessionId) throws Throwable {
		return null;
	}

	/**
	 * A grid replica is an in-memory document that represents a 'copy' of the grid.
	 * Each replica is identified by a unique replicaId, issued by the 'hub'. A user
	 * can have more then one replica at time (i.e. using multiple
	 * browser/tabs/machines). A user is limited to 10 replicas per-hour
	 * per-grid-session.
	 * 
	 * @param userId
	 * @param sessionId
	 * @param request
	 * @return
	 */
	@RequiredScope({ view, modify })
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = UrlHelpers.GRID_SESSION_ID_REPLICA, method = RequestMethod.PUT)
	public @ResponseBody CreateReplicaResponse createReplica(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId, @PathVariable String sessionId,
			@RequestBody CreateReplicaRequest request) {

		return null;
	}

}
