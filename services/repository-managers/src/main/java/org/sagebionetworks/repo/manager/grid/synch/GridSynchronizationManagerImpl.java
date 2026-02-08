package org.sagebionetworks.repo.manager.grid.synch;

import org.sagebionetworks.repo.manager.grid.PatchUtils;
import org.sagebionetworks.repo.manager.grid.internal.replica.change.IntendedChangePublisher;
import org.sagebionetworks.repo.manager.grid.internal.replica.change.PatchBuilderPublisher;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.GridHeader;
import org.sagebionetworks.repo.manager.grid.synch.core.Merge;
import org.sagebionetworks.repo.manager.grid.synch.core.SynchronizationLogic;
import org.sagebionetworks.repo.manager.grid.synch.handler.CopyHandler;
import org.sagebionetworks.repo.manager.grid.synch.handler.CopyHandlerProvider;
import org.sagebionetworks.repo.manager.grid.synch.handler.SourceHandler;
import org.sagebionetworks.repo.manager.grid.synch.handler.SourceHandlerProvider;
import org.sagebionetworks.repo.manager.grid.synch.io.RowReader;
import org.sagebionetworks.repo.manager.grid.synch.row.RowCopy;
import org.sagebionetworks.repo.manager.grid.synch.row.RowMerge;
import org.sagebionetworks.repo.manager.grid.synch.row.RowSource;
import org.sagebionetworks.repo.manager.grid.synch.schema.SchemaCopy;
import org.sagebionetworks.repo.manager.grid.synch.schema.SchemaSource;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dao.asynch.AsyncJobProgressCallback;
import org.sagebionetworks.repo.model.grid.GridConnectionInfo;
import org.sagebionetworks.repo.model.grid.GridSession;
import org.springframework.stereotype.Service;

@Service
public class GridSynchronizationManagerImpl implements GridSynchronizationManager {

	private final PatchBuilderPublisher patchBuilderPublisher;
	private final SourceHandlerProvider sourceHandlerProvdier;
	private final CopyHandlerProvider copyHandlerProvider;
	private final SynchronizationLogic logic;
	private final SynchronizeProvider synchronizeProvider;

	public GridSynchronizationManagerImpl(SourceHandlerProvider sourceHandlerProvdier,
			CopyHandlerProvider copyHandlerProvider, SynchronizationLogic logic, SynchronizeProvider synchronizeProvider,
			PatchBuilderPublisher patchBuilderPublisher) {
		super();
		this.patchBuilderPublisher = patchBuilderPublisher;
		this.sourceHandlerProvdier = sourceHandlerProvdier;
		this.copyHandlerProvider = copyHandlerProvider;
		this.logic = logic;
		this.synchronizeProvider = synchronizeProvider;
	}

	@Override
	public void synchronizeCopyWithSource(AsyncJobProgressCallback callback, UserInfo user, GridSession session)
			throws Exception {
		try (CopyHandler copyHandler = copyHandlerProvider.createCopyHandler(session);
				SourceHandler sourceHandler = sourceHandlerProvdier.createNewProvider(callback, user, session,
						copyHandler.getGridSource());
				RowReader sourceReader = sourceHandler.getSourceRowReader()) {

			try (IntendedChangePublisher icp = newIntendedChangePublisher(copyHandler)) {
				// Phase one synchronize the schema
				SchemaCopy schemaCopy = synchronizeProvider.getSchemaCopy(icp, copyHandler);
				SchemaSource schemaSource = synchronizeProvider.getSchemaSource(sourceHandler);
				logic.synchronize(schemaCopy, schemaSource, Merge.noOp());

				// Phase two synchronize the rows
				RowCopy rowCopy = synchronizeProvider.getRowCopy(icp, schemaCopy.getFinalSchema(), copyHandler);
				RowSource rowSource = synchronizeProvider.getRowSource(sourceReader, sourceHandler);
				RowMerge rowMerge = synchronizeProvider.getRowMerge(logic, icp, schemaCopy.getFinalSchema(), copyHandler,
						sourceHandler);
				logic.synchronize(rowCopy, rowSource, rowMerge);
			}
		}
	}

	IntendedChangePublisher newIntendedChangePublisher(CopyHandler copyHandler) {
		GridHeader header = copyHandler.getHeader();
		GridConnectionInfo connInfo = copyHandler.getConnectionInfo();
		return new IntendedChangePublisher(connInfo, header.getClockSequenceMaximum(), patchBuilderPublisher,
				PatchUtils.MAX_CHANGE_SET_SIZE);
	}
}
