package org.sagebionetworks.repo.manager.grid.synch.handler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.sagebionetworks.grid.db.GridIndexDao;
import org.sagebionetworks.repo.manager.grid.GridManager;
import org.sagebionetworks.repo.manager.grid.internal.replica.GridReplicaSupport;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.Column;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.GridHeader;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.RowView;
import org.sagebionetworks.repo.manager.grid.internal.replica.view.GridReplicaViewManager;
import org.sagebionetworks.repo.manager.grid.internal.replica.view.query.QueryElement;
import org.sagebionetworks.repo.manager.grid.synch.row.CopyCell;
import org.sagebionetworks.repo.manager.grid.synch.row.CopyRow;
import org.sagebionetworks.repo.manager.grid.synch.row.CopyRowImpl;
import org.sagebionetworks.repo.model.dbo.grid.GridSource;
import org.sagebionetworks.repo.model.grid.EventSource;
import org.sagebionetworks.repo.model.grid.GridConnectionInfo;
import org.sagebionetworks.repo.model.grid.GridSession;
import org.sagebionetworks.repo.model.grid.node.RGANode;
import org.sagebionetworks.repo.model.grid.patch.ConValue;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;
import org.sagebionetworks.repo.web.NotFoundException;

public class CopyHandlerImpl implements CopyHandler {

	private final GridReplicaViewManager gridReplicaViewManager;
	private final GridSource gridSource;
	private final GridConnectionInfo connection;
	private final GridHeader header;
	private final LogicalTimestamp lastRowsRgaNodeId;
	private final Map<Integer, String> indexToColumnMap;

	public CopyHandlerImpl(GridReplicaViewManager gridReplicaViewManager, GridReplicaSupport gridReplicaSupport,
			GridIndexDao gridIndexDao, GridManager gridManager, GridSession gridSession) {
		this.gridReplicaViewManager = gridReplicaViewManager;
		this.header = gridReplicaSupport.getGridHeaderOrThrow(gridSession);
		this.gridSource = getSourceOrThrow(gridManager, gridSession.getSessionId());
		this.connection = getConnectionOrThrow(gridManager, gridSession.getSessionId());
		this.lastRowsRgaNodeId = getLastRowsRgaNodeId(gridIndexDao, header);
		this.indexToColumnMap = buildIndexToColumnMap(header.getOrderedColumns());
	}

	private GridSource getSourceOrThrow(GridManager gridManager, String sessionId) {
		return gridManager.getSessionSource(sessionId).orElseThrow(() -> new NotFoundException("Grid: " + sessionId));
	}

	private GridConnectionInfo getConnectionOrThrow(GridManager gridManager, String sessionId) {
		return gridManager.getSingletonConnection(sessionId, EventSource.INTERNAL)
				.orElseThrow(() -> new NotFoundException("Grid: " + sessionId));
	}

	private LogicalTimestamp getLastRowsRgaNodeId(GridIndexDao gridIndexDao, GridHeader header) {
		return gridIndexDao.getRgaLastNode(header.getSessionId(), header.getReplicaId(), header.getRowsId())
				.map(RGANode::getNodeId).orElse(null);
	}

	private Map<Integer, String> buildIndexToColumnMap(List<Column> columns) {
		Map<Integer, String> map = new HashMap<>(columns.size());
		for (int i = 0; i < columns.size(); i++) {
			map.put(i, columns.get(i).getName());
		}
		return map;
	}

	@Override
	public void close() {
	}

	@Override
	public GridHeader getHeader() {
		return header;
	}

	@Override
	public GridConnectionInfo getConnectionInfo() {
		return connection;
	}

	@Override
	public Iterator<CopyRow> getRows() {
		Iterator<RowView> rowIterator = gridReplicaViewManager.getQueryIterator(header, new QueryElement());
		return new Iterator<CopyRow>() {

			@Override
			public CopyRow next() {
				RowView rowView = rowIterator.next();
				return createCopyRow(rowView);
			}

			@Override
			public boolean hasNext() {
				return rowIterator.hasNext();
			}
		};
	}

	private CopyRow createCopyRow(RowView rowView) {
		LogicalTimestamp vectorId = rowView.getRowObject().getData().getVectorId();
		List<CopyCell> cells = createCopyCells(rowView.getCells());
		return new CopyRowImpl().setCells(cells).setRgaNodeId(rowView.getArrNodeId()).setVectorNodeId(vectorId);
	}

	private List<CopyCell> createCopyCells(List<ConValue> cellData) {
		List<CopyCell> cells = new ArrayList<>(cellData.size());
		for (int i = 0; i < cellData.size(); i++) {
			ConValue value = cellData.get(i);
			// TODO: Set using the ConstantNode replicaId compared to the
			// connection.getReplicaId() to set:
			boolean wasChangedByUser = false;
			String columnName = indexToColumnMap.get(i);
			cells.add(new CopyCell().setName(columnName).setValue(value).setWasChangedByUser(wasChangedByUser));
		}
		return cells;
	}

	@Override
	public GridSource getGridSource() {
		return gridSource;
	}

	@Override
	public LogicalTimestamp getLastRowsRgaNodeId() {
		return lastRowsRgaNodeId;
	}

	@Override
	public Long getInternalReplicaId() {
		return connection.getReplicaId();
	}
}
