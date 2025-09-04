package org.sagebionetworks.repo.web.controller;

import org.sagebionetworks.repo.model.grid.sql.Query;
import org.sagebionetworks.repo.model.grid.sql.QueryResult;
import org.sagebionetworks.repo.model.schema.JsonSchema;
import org.sagebionetworks.repo.web.UrlHelpers;
import org.sagebionetworks.repo.web.rest.doc.ControllerInfo;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Temp controller to generate the open api.
 * 
 */
@Controller
@ControllerInfo(displayName = "GridAgent", path = "repo/v1")
@RequestMapping(UrlHelpers.REPO_PATH)
public class GridTempController {

	/**
	 * Get the JSON Schema associated with the grid session. This schema is used to
	 * validate the rows of grid.
	 * 
	 * @param userId
	 * @param asyncToken
	 * @return The JSON schema used to validate the rows of the grid session.
	 * @throws Throwable
	 */
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = "/grid/json-schema", method = RequestMethod.GET)
	public @ResponseBody JsonSchema getJsonSchema() throws Throwable {

		return null;
	}

	/**
	 * 
	 * @param query
	 * @return The result of the query.
	 */
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = { "/grid/query" }, method = RequestMethod.POST)
	public @ResponseBody QueryResult query(@RequestBody(required = true) Query query) {

		return null;
	}
}
