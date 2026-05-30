package org.sagebionetworks.repo.manager.grid.create;

import java.io.IOException;
import java.util.Set;

import org.sagebionetworks.repo.manager.grid.SnapshotRowHandler;
import org.sagebionetworks.repo.model.dao.table.RowHandler;
import org.sagebionetworks.repo.model.table.Row;

/**
 * A RowHandler that collects benefactor IDs from each row while delegating
 * row processing and resource cleanup to the underlying SnapshotRowHandler.
 */
public class BenefactorCollectingRowHandler implements RowHandler {

	private final SnapshotRowHandler delegate;
	private final Set<Long> benefactorIds;

	public BenefactorCollectingRowHandler(SnapshotRowHandler delegate, Set<Long> benefactorIds) {
		this.delegate = delegate;
		this.benefactorIds = benefactorIds;
	}

	@Override
	public void nextRow(Row row) {
		if (row.getBenefactorId() != null) {
			benefactorIds.add(row.getBenefactorId());
		}
		delegate.nextRow(row);
	}

	@Override
	public void close() throws IOException {
		delegate.close();
	}

}
