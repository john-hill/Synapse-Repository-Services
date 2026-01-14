package org.sagebionetworks.repo.manager.grid.internal.replica.synch;

import org.sagebionetworks.repo.manager.grid.internal.replica.model.RowView;
import org.sagebionetworks.repo.model.grid.SynchronizationOperation;

public interface SynchronizationRowHandler {

	SynchronizationResult processRow(RowView row, SynchronizationOperation operation);

}
