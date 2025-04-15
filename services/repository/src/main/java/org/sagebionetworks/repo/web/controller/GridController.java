package org.sagebionetworks.repo.web.controller;

import static org.sagebionetworks.repo.model.oauth.OAuthScope.modify;
import static org.sagebionetworks.repo.model.oauth.OAuthScope.view;

import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.asynch.AsyncJobId;
import org.sagebionetworks.repo.model.grid.CreateGridRequest;
import org.sagebionetworks.repo.model.grid.CreateGridResponse;
import org.sagebionetworks.repo.model.grid.GetCellCrdtRequest;
import org.sagebionetworks.repo.model.grid.GetCellCrdtResponse;
import org.sagebionetworks.repo.model.grid.GetCellValueRequest;
import org.sagebionetworks.repo.model.grid.GetCellValueResponse;
import org.sagebionetworks.repo.model.grid.GetViewportCellsRequest;
import org.sagebionetworks.repo.model.grid.GetViewportCellsResponse;
import org.sagebionetworks.repo.model.grid.GridQueryRequest;
import org.sagebionetworks.repo.model.grid.GridQueryResponse;
import org.sagebionetworks.repo.model.grid.GridSession;
import org.sagebionetworks.repo.model.grid.MapCellIdToCellAddressRequest;
import org.sagebionetworks.repo.model.grid.MapCellIdToCellAddressResponse;
import org.sagebionetworks.repo.model.grid.MergeCellCrdtRequest;
import org.sagebionetworks.repo.model.grid.MergeCellCrdtResponse;
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
	 * Get the cell level CRDTs for the provided cell IDs.
	 * 
	 * @param userId
	 * @param sessionId
	 * @param request
	 * @return
	 * @throws Throwable
	 */
	@RequiredScope({ view })
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = UrlHelpers.GRID_SESSION_ID_CELL_CRDT, method = RequestMethod.GET)
	public @ResponseBody GetCellCrdtResponse getCellCrdts(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId, @PathVariable String sessionId,
			@RequestBody GetCellCrdtRequest request) throws Throwable {
		return null;
	}

	/**
	 * Get the current cell values for the provided cell IDs.
	 * 
	 * @param userId
	 * @param sessionId
	 * @param request
	 * @return
	 * @throws Throwable
	 */
	@RequiredScope({ view })
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = UrlHelpers.GRID_SESSION_ID_CELL_VALUE, method = RequestMethod.GET)
	public @ResponseBody GetCellValueResponse getCellValues(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId, @PathVariable String sessionId,
			@RequestBody GetCellValueRequest request) throws Throwable {
		return null;
	}

	/**
	 * Start a job to get the cells within the provide viewport.
	 * 
	 * @param userId
	 * @param request
	 * @return
	 */
	@RequiredScope({ view, modify })
	@ResponseStatus(HttpStatus.CREATED)
	@RequestMapping(value = UrlHelpers.GRID_SESSION_ID_VIEWPORT_CELL_ASYNC_START, method = RequestMethod.POST)
	public @ResponseBody AsyncJobId startGetViewportCells(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@RequestBody GetViewportCellsRequest request) {

		return null;
	}

	/**
	 * Get the results of the job started with:
	 * <a href="POST.grid.session.sessionId.viewport.cell.async.start">POST
	 * /grid/session/{sessionId}/viewport/cell/async/start</a>
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
	@RequestMapping(value = UrlHelpers.GRID_SESSION_ID_VIEWPORT_CELL_ASYNC_GET, method = RequestMethod.GET)
	public @ResponseBody GetViewportCellsResponse getViewportCells(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId, @PathVariable String asyncToken)
			throws Throwable {
		return null;
	}

	/**
	 * Start a job to get the map of cell address relative to a view port given cell
	 * IDs..
	 * 
	 * @param userId
	 * @param request
	 * @return
	 */
	@RequiredScope({ view, modify })
	@ResponseStatus(HttpStatus.CREATED)
	@RequestMapping(value = UrlHelpers.GRID_SESSION_ID_VIEWPORT_MAP_ASYNC_START, method = RequestMethod.POST)
	public @ResponseBody AsyncJobId startMapViewportCells(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@RequestBody MapCellIdToCellAddressRequest request) {

		return null;
	}

	/**
	 * Get the results of the job started with:
	 * <a href="POST.grid.session.sessionId.viewport.map.async.start">POST
	 * /grid/session/{sessionId}/viewport/map/async/start</a>
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
	@RequestMapping(value = UrlHelpers.GRID_SESSION_ID_VIEWPORT_MAP_ASYNC_GET, method = RequestMethod.GET)
	public @ResponseBody MapCellIdToCellAddressResponse getMapViewportCell(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId, @PathVariable String asyncToken)
			throws Throwable {
		return null;
	}

	/**
	 * Start a job to merge a batch of cell CRDT into the grid. A change message
	 * will be published for each cell that is updated.
	 * 
	 * @param userId
	 * @param request
	 * @return
	 */
	@RequiredScope({ view, modify })
	@ResponseStatus(HttpStatus.CREATED)
	@RequestMapping(value = UrlHelpers.GRID_SESSION_ID_CELL_CRDT_MERGE_START, method = RequestMethod.POST)
	public @ResponseBody AsyncJobId startCellMerge(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@RequestBody MergeCellCrdtRequest request) {

		return null;
	}

	/**
	 * Get the results of the job started with:
	 * <a href="POST.grid.session.sessionId.viewport.map.async.start">POST
	 * /grid/session/{sessionId}/viewport/map/async/start</a>
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
	@RequestMapping(value = UrlHelpers.GRID_SESSION_ID_CELL_CRDT_MERGE_GET, method = RequestMethod.GET)
	public @ResponseBody MergeCellCrdtResponse getCellMerge(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId, @PathVariable String asyncToken)
			throws Throwable {
		return null;
	}

	/**
	 * An Agent query against the grid. This call does not need to be asynchronous
	 * if it is made via return_control. We are considering defining this with Model
	 * Context Protocol (MCP).
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
	 * An Agent SQL update request. Each cell update with extend the cell's CRDT
	 * history with attribution of aggent_assist=true. This call does not need to be
	 * asynchronous if it is made via return_control. We are considering defining
	 * this with Model Context Protocol (MCP).
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

}
