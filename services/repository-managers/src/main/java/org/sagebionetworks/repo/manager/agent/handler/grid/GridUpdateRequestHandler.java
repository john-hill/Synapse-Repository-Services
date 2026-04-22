package org.sagebionetworks.repo.manager.agent.handler.grid;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.sagebionetworks.repo.manager.agent.handler.HttpCode;
import org.sagebionetworks.repo.manager.agent.handler.HttpMethod;
import org.sagebionetworks.repo.manager.agent.handler.OpenApiReturnControlHandler;
import org.sagebionetworks.repo.manager.agent.handler.ReturnControlEvent;
import org.sagebionetworks.repo.manager.grid.GridManager;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.GridHeader;
import org.sagebionetworks.repo.manager.grid.internal.replica.view.GridReplicaViewManager;
import org.sagebionetworks.repo.model.agent.GridAgentSessionContext;
import org.sagebionetworks.repo.model.grid.EventSource;
import org.sagebionetworks.repo.model.grid.GridConnectionInfo;
import org.sagebionetworks.repo.model.grid.update.GridUpdateResponse;
import org.sagebionetworks.repo.model.jdo.JDOSecondaryPropertyUtils;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.stereotype.Service;

@Service
public class GridUpdateRequestHandler implements OpenApiReturnControlHandler {

	private static final Logger log = LogManager.getLogger(GridUpdateRequestHandler.class);

	private final GridManager gridManager;
	private final GridReplicaViewManager gridViewManager;

	public GridUpdateRequestHandler(GridManager gridManager, GridReplicaViewManager gridViewManager) {
		this.gridManager = gridManager;
		this.gridViewManager = gridViewManager;
	}

	@Override
	public String getActionGroup() {
		return "org_sage_grid_one";
	}

	@Override
	public boolean needsWriteAccess() {
		return false;
	}

	@Override
	public String handleEvent(ReturnControlEvent event) throws Exception {
		JSONObject updateRequestRaw = extractRequest(event);
		GridAgentSessionContext context = getSessionContext(event);
		GridConnectionInfo internalConnection = getInternalConnection(context);
		GridHeader header = getGridHeader(context, internalConnection);
		GridConnectionInfo agentConnection = getAgentConnection(context);

		JSONArray updateBatch = updateRequestRaw.getJSONObject("update").getJSONArray("batch");
		List<Long> updateCounts = new ArrayList<>();
		for (int i = 0; i < updateBatch.length(); i++) {
			// call under test
			updateCounts.add(gridManager.executeGridUpdate(header, agentConnection, updateBatch.getJSONObject(i)));
		}
		return buildResponseJSON(updateCounts);
	}

	GridAgentSessionContext getSessionContext(ReturnControlEvent event) {
		return event.getSessionContext(GridAgentSessionContext.class)
				.orElseThrow(() -> new IllegalArgumentException("GridAgentSessionContext cannot be null"));
	}

	GridConnectionInfo getInternalConnection(GridAgentSessionContext context) {
		return gridManager.getSingletonConnection(context.getGridSessionId(), EventSource.INTERNAL)
				.orElseThrow(() -> new IllegalArgumentException("Cannot get an internal grid connection."));
	}

	GridHeader getGridHeader(GridAgentSessionContext context, GridConnectionInfo internalConnection) {
		return gridViewManager
				.readHeader(context.getGridSessionId(), internalConnection.getReplicaId(), context.getUsersReplicaId())
				.orElseThrow(() -> new IllegalArgumentException("Cannot read the grid header."));
	}

	GridConnectionInfo getAgentConnection(GridAgentSessionContext context) {
		return gridManager.getConnection(context.getGridSessionId(), context.getAgentsReplicaId()).orElseThrow(
				() -> new IllegalArgumentException("Cannot get an agent grid connection."));
	}

	String buildResponseJSON(List<Long> updateCount) {
		String json = JDOSecondaryPropertyUtils
				.createJSONFromObject(new GridUpdateResponse().setUpdateResults(updateCount)
						.setTotalRowsUpdated(updateCount.stream().mapToLong(Long::longValue).sum()));
		log.info("response JSON: {}", json);
		return json;
	}

	JSONObject extractRequest(ReturnControlEvent event) {
		ValidateArgument.required(event, "event");
		String body = event.getRequestBody()
				.orElseThrow(() -> new IllegalArgumentException("Request body cannot be null."));
		log.info("request body: {}", body);
		return new JSONObject(body);
	}

	@Override
	public String getPath() {
		return "/repo/v1/grid/update";
	}

	@Override
	public HttpMethod getHttpMethod() {
		return HttpMethod.put;
	}

	@Override
	public HttpCode getSuccessHttpCode() {
		return HttpCode.ok;
	}
}
