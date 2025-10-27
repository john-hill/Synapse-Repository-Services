package org.sagebionetworks.repo.manager.agent.handler.grid;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.sagebionetworks.repo.manager.agent.handler.HttpCode;
import org.sagebionetworks.repo.manager.agent.handler.HttpMethod;
import org.sagebionetworks.repo.manager.agent.handler.OpenApiReturnControlHandler;
import org.sagebionetworks.repo.manager.agent.handler.ReturnControlEvent;
import org.sagebionetworks.repo.manager.grid.GridManager;
import org.sagebionetworks.repo.manager.grid.PatchUtils;
import org.sagebionetworks.repo.manager.grid.internal.replica.change.IntendedChangePublisher;
import org.sagebionetworks.repo.manager.grid.internal.replica.change.PatchBuilderPublisher;
import org.sagebionetworks.repo.manager.grid.internal.replica.change.UpdateRowChange;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.Column;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.GridHeader;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.RowView;
import org.sagebionetworks.repo.manager.grid.internal.replica.view.GridReplicaViewManager;
import org.sagebionetworks.repo.manager.grid.internal.replica.view.query.QueryElement;
import org.sagebionetworks.repo.manager.grid.internal.replica.view.query.filter.FilterElement;
import org.sagebionetworks.repo.manager.grid.internal.replica.view.query.filter.FilterTranslation;
import org.sagebionetworks.repo.model.agent.GridAgentSessionContext;
import org.sagebionetworks.repo.model.grid.EventSource;
import org.sagebionetworks.repo.model.grid.GridConnectionInfo;
import org.sagebionetworks.repo.model.grid.patch.ConType;
import org.sagebionetworks.repo.model.grid.patch.ConValue;
import org.sagebionetworks.repo.model.grid.update.GridUpdateRequest;
import org.sagebionetworks.repo.model.grid.update.GridUpdateResponse;
import org.sagebionetworks.repo.model.grid.update.SetValue;
import org.sagebionetworks.repo.model.grid.update.Update;
import org.sagebionetworks.repo.model.jdo.JDOSecondaryPropertyUtils;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.stereotype.Service;

@Service
public class GridUpdateRequestHandler implements OpenApiReturnControlHandler {

	private static final Logger log = LogManager.getLogger(GridUpdateRequestHandler.class);

	private final GridManager gridManager;
	private final GridReplicaViewManager gridViewManager;
	private final PatchBuilderPublisher patchBuilderPublisher;

	public GridUpdateRequestHandler(GridManager gridManager, GridReplicaViewManager gridViewManager,
			PatchBuilderPublisher patchBuilderPublisher) {
		this.gridManager = gridManager;
		this.gridViewManager = gridViewManager;
		this.patchBuilderPublisher = patchBuilderPublisher;
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
		GridUpdateRequest request = extractRequest(event);
		ValidateArgument.required(request.getUpdate(), "update");
		Update update = request.getUpdate();
		List<SetValue> set = update.getSet();

		GridAgentSessionContext context = event.getSessionContext(GridAgentSessionContext.class)
				.orElseThrow(() -> new IllegalArgumentException("GridAgentSessionContext cannot be null"));

		GridConnectionInfo internalConnection = gridManager
				.getSingletonConnection(context.getGridSessionId(), EventSource.INTERNAL)
				.orElseThrow(() -> new IllegalArgumentException("Cannot get a grid connection."));

		GridHeader header = gridViewManager
				.readHeader(context.getGridSessionId(), internalConnection.getReplicaId(), context.getUsersReplicaId())
				.orElseThrow(() -> new IllegalArgumentException("Grid session does not exist"));

		List<FilterElement> filters = update.getFilters() == null ? Collections.emptyList()
				: update.getFilters().stream().map(FilterTranslation::translate).collect(Collectors.toList());

		GridConnectionInfo agentConnection = gridManager
				.getConnection(context.getGridSessionId(), context.getAgentsReplicaId()).orElseThrow(
						() -> new IllegalArgumentException("Grid connection does not exist for the agent replica."));

		Integer[] indexArray = createIndexArray(set, header);
		long updateCount = 0;
		Iterator<RowView> rows = gridViewManager.getQueryIterator(header,
				new QueryElement().setWhere(filters).setLimit(update.getLimit()));

		// The auto-generated class loses the raw JSON null vs undefined info, which we need because it has semantic meaning for updates.
		// We can re-construct that by getting the raw JSON object.
		JSONObject updateRequestRaw = new JSONObject(event.getRequestBody().get());

		try (IntendedChangePublisher icp = newIntendedChangePublisher(agentConnection, header.getClockSequenceMaximum(),
				patchBuilderPublisher)) {
			while (rows.hasNext()) {
				RowView row = rows.next();
				List<ConValue> updates = new ArrayList<>();
				for (int i = 0; i < set.size(); i++) {
					SetValue sv = set.get(i);
					ConValue toAdd = new ConValue(ConType.fromValue(sv.getValue()), sv.getValue());
					JSONObject rawSetValue = updateRequestRaw.optJSONObject("update").optJSONArray("set").optJSONObject(i);
					if (rawSetValue.opt("value") == JSONObject.NULL) {
						toAdd = new ConValue(ConType.NULL, null);
					} else if (!rawSetValue.has("value")) {
						toAdd = new ConValue(ConType.UNDEFINED, null);
					}
					updates.add(toAdd);
				}
				icp.publish(new UpdateRowChange(row.getRowObject().getData().getVectorId(), updates, indexArray));
				updateCount++;
			}
		}

		return buildResponseJSON(updateCount);
	}

	IntendedChangePublisher newIntendedChangePublisher(GridConnectionInfo connInfo, Long maxClockSeq,
			PatchBuilderPublisher publisher) {
		return new IntendedChangePublisher(connInfo, maxClockSeq, publisher, PatchUtils.MAX_CHANGE_SET_SIZE);
	}

	String buildResponseJSON(Long updateCount) {
		String json = JDOSecondaryPropertyUtils
				.createJSONFromObject(new GridUpdateResponse().setRowsUpdated(updateCount));
		log.info("response JSON: {}", json);
		return json;
	}

	GridUpdateRequest extractRequest(ReturnControlEvent event) {
		ValidateArgument.required(event, "event");
		String body = event.getRequestBody()
				.orElseThrow(() -> new IllegalArgumentException("Request body cannot be null."));
		log.info("request body: {}", body);
		return JDOSecondaryPropertyUtils.createObjectFromJSON(GridUpdateRequest.class, body);
	}

	Integer[] createIndexArray(List<SetValue> set, GridHeader header) {
		ValidateArgument.required(set, "set");
		ValidateArgument.required(header, "header");
		ValidateArgument.required(header.getOrderedColumns(), "header.orderedColumns");
		Map<String, Integer> indexByName = header.getOrderedColumns().stream()
				.collect(Collectors.toMap(Column::getName, Column::getVectorIndex));
		return set.stream().map(s -> {
			Integer idx = indexByName.get(s.getColumnName());
			if (idx == null) {
				throw new IllegalArgumentException("Column name: " + s.getColumnName() + " not found.");
			}
			return idx;
		}).toArray(Integer[]::new);
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
