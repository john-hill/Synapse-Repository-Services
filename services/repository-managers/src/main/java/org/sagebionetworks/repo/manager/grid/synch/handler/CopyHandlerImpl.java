package org.sagebionetworks.repo.manager.grid.synch.handler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

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
import org.sagebionetworks.repo.model.grid.node.ConstantNode;
import org.sagebionetworks.repo.model.grid.node.RGANode;
import org.sagebionetworks.repo.model.grid.node.VectorNode;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;
import org.sagebionetworks.repo.web.NotFoundException;

public class CopyHandlerImpl implements CopyHandler {

	private static final int BATCH_SIZE = 100;

	private final GridReplicaViewManager gridReplicaViewManager;
	private final GridIndexDao gridIndexDao;
	private final GridSession gridSession;
	private final GridSource gridSource;
	private final GridConnectionInfo connection;
	private final GridHeader header;
	private final LogicalTimestamp lastRgaNodeId;
	private final Map<Integer, String> indexToColumnMap;

	public CopyHandlerImpl(GridReplicaViewManager gridReplicaViewManager, GridReplicaSupport gridReplicaSupport,
			GridIndexDao gridIndexDao, GridManager gridManager, GridSession gridSession) {
		this.gridReplicaViewManager = gridReplicaViewManager;
		this.gridIndexDao = gridIndexDao;
		this.gridSession = gridSession;
		this.header = gridReplicaSupport.getGridHeaderOrThrow(gridSession);
		this.gridSource = gridManager.getSessionSource(gridSession.getSessionId())
				.orElseThrow(() -> new NotFoundException("Grid: " + gridSession.getSessionId()));
		this.connection = gridManager.getSingletonConnection(gridSession.getSessionId(), EventSource.INTERNAL)
				.orElseThrow(() -> new NotFoundException("Grid: " + gridSession.getSessionId()));
		this.lastRgaNodeId = gridIndexDao
				.getRgaLastNode(header.getSessionId(), header.getReplicaId(), header.getRowsId())
				.map(RGANode::getNodeId).orElse(null);
		this.indexToColumnMap = buildIndexToColumnMap(header.getOrderedColumns());
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
		return new BatchingCopyRowIterator(gridReplicaViewManager.getQueryIterator(header, new QueryElement()));
	}

	@Override
	public GridSource getGridSource() {
		return gridSource;
	}

	@Override
	public LogicalTimestamp getLastRgaNodeId() {
		return lastRgaNodeId;
	}

	private class BatchingCopyRowIterator implements Iterator<CopyRow> {
		private final Iterator<RowView> rowIterator;
		private final List<CopyRow> currentBatch = new ArrayList<>(BATCH_SIZE);
		private int batchIndex = 0;

		public BatchingCopyRowIterator(Iterator<RowView> rowIterator) {
			this.rowIterator = rowIterator;
		}

		@Override
		public boolean hasNext() {
			return batchIndex < currentBatch.size() || rowIterator.hasNext();
		}

		@Override
		public CopyRow next() {
			if (!hasNext()) {
				throw new NoSuchElementException();
			}

			if (batchIndex >= currentBatch.size()) {
				loadNextBatch();
				batchIndex = 0;
			}

			return currentBatch.get(batchIndex++);
		}

		private void loadNextBatch() {
			currentBatch.clear();

			List<RowView> rowViews = collectRowBatch();
			if (rowViews.isEmpty()) {
				return;
			}

			Map<LogicalTimestamp, VectorNode> vectorMap = fetchVectors(rowViews);
			buildCopyRows(rowViews, vectorMap);
		}

		private List<RowView> collectRowBatch() {
			List<RowView> rowViews = new ArrayList<>(BATCH_SIZE);
			while (rowIterator.hasNext() && rowViews.size() < BATCH_SIZE) {
				RowView rowView = rowIterator.next();
				validateRowView(rowView);
				rowViews.add(rowView);
			}
			return rowViews;
		}

		private Map<LogicalTimestamp, VectorNode> fetchVectors(List<RowView> rowViews) {
			List<LogicalTimestamp> vectorIds = rowViews.stream().map(rv -> rv.getRowObject().getData().getVectorId())
					.collect(Collectors.toList());

			List<VectorNode> vectors = gridIndexDao.getVectors(gridSession.getSessionId(), connection.getReplicaId(),
					vectorIds);

			return vectors.stream().collect(Collectors.toMap(VectorNode::getId, v -> v));
		}

		private void buildCopyRows(List<RowView> rowViews, Map<LogicalTimestamp, VectorNode> vectorMap) {
			for (RowView rowView : rowViews) {
				LogicalTimestamp vectorId = rowView.getRowObject().getData().getVectorId();
				VectorNode vectorNode = vectorMap.get(vectorId);

				if (vectorNode == null) {
					throw new NotFoundException(
							"Vector not found: " + vectorId + " for grid: " + gridSession.getSessionId());
				}
				List<CopyCell> cells = new ArrayList<>();
				for (Entry<Integer, ConstantNode> entry : vectorNode.getValues().entrySet()) {
					Integer index = entry.getKey();
					ConstantNode node = entry.getValue();
					String columnName = indexToColumnMap.get(index);
					boolean chagnedByUser = !connection.getReplicaId().equals(node.getId().getReplicaId());
					cells.add(new CopyCell().setName(columnName).setValue(node.getConValue())
							.setWasChangedByUser(chagnedByUser));
				}
				currentBatch.add(new CopyRowImpl().setCells(cells).setRgaNodeId(rowView.getArrNodeId())
						.setVectorNodeId(vectorId));
			}
		}

		private void validateRowView(RowView rowView) {
			if (rowView.getRowObject() == null || rowView.getRowObject().getData() == null) {
				throw new IllegalStateException("RowView missing data for grid: " + gridSession.getSessionId());
			}
		}
	}

	@Override
	public Long getInternalReplicaId() {
		return connection.getReplicaId();
	}
}
