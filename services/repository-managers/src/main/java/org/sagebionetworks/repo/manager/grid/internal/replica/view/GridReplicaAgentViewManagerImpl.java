package org.sagebionetworks.repo.manager.grid.internal.replica.view;

import java.util.List;
import java.util.Optional;

import org.sagebionetworks.repo.manager.grid.GridManager;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.GridHeader;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.RowView;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.grid.GridSession;
import org.sagebionetworks.repo.model.grid.sql.GridQueryRequest;
import org.sagebionetworks.repo.model.grid.sql.GridQueryResponse;
import org.sagebionetworks.repo.model.grid.sql.Query;
import org.sagebionetworks.repo.model.grid.sql.QueryResult;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.stereotype.Service;

@Service
public class GridReplicaAgentViewManagerImpl implements GridReplicaAgentViewManager {

	private final GridReplicaViewManager viewManager;
	private final GridManager gridManager;

	public GridReplicaAgentViewManagerImpl(GridReplicaViewManager viewManager, GridManager gridManager) {
		super();
		this.viewManager = viewManager;
		this.gridManager = gridManager;
	}

	@Override
	public GridQueryResponse queryGrid(UserInfo user, GridQueryRequest request) {
		ValidateArgument.required(request, "request");
		ValidateArgument.required(request.getQuery(), "request.query");
		ValidateArgument.required(user, "user");
		// Note: User must have access to get the grid's session.
		GridSession session = gridManager.getGridSession(user, request.getGridSessionId());
		Optional<GridHeader> headerOp = viewManager.readHeader(request.getGridSessionId(), request.getReplicaId());
		if (headerOp.isEmpty()) {
			return new GridQueryResponse().setResults(new QueryResult());
		}
		Query query = request.getQuery();
		Long limit = query.getLimit() != null ? query.getLimit() : 10;
		Long offset = query.getOffset() != null ? query.getOffset() : 0;
		List<RowView> rowViews = viewManager.querySinglePage(headerOp.get(), null, limit, offset);

		return new GridQueryResponse().setResults(translateResults(headerOp.get(), rowViews));
	}

	QueryResult translateResults(GridHeader header, List<RowView> rowViews) {

		return new QueryResult();
	}

}
