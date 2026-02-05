package org.sagebionetworks.repo.manager.grid.synch;

import java.util.List;

import org.sagebionetworks.repo.manager.grid.internal.replica.change.IntendedChangePublisher;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.Column;
import org.sagebionetworks.repo.manager.grid.synch.v2.RowCopy;
import org.sagebionetworks.repo.manager.grid.synch.v2.RowMerge;
import org.sagebionetworks.repo.manager.grid.synch.v2.RowSource;
import org.sagebionetworks.repo.manager.grid.synch.v2.SchemaCopy;
import org.sagebionetworks.repo.manager.grid.synch.v2.SchemaSource;
import org.sagebionetworks.repo.manager.grid.synch.v2.SynchronizationLogic;

public interface SynchronizeProvider {

	SchemaCopy getSchemaCopy(IntendedChangePublisher intendedChangePublisher, CopyReader reader);

	SchemaSource getSchemaSource(SourceHandler handler);

	RowCopy getRowCopy(IntendedChangePublisher intendedChangePublisher, List<Column> finalSchema, CopyReader reader);

	RowSource getRowSource(RowReader sourceReader, SourceHandler handler);

	RowMerge getRowMerge(SynchronizationLogic logic, IntendedChangePublisher intendedChangePublisher,
			List<Column> finalSchema, CopyReader reader, SourceHandler handler);

}
