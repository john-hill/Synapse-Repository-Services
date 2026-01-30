package org.sagebionetworks.repo.manager.grid.synch;

import org.sagebionetworks.grid.db.GridIndexDao;
import org.sagebionetworks.repo.manager.grid.GridManager;
import org.sagebionetworks.repo.manager.grid.internal.replica.GridReplicaSupport;
import org.sagebionetworks.repo.manager.grid.internal.replica.view.GridReplicaViewManager;
import org.sagebionetworks.repo.model.grid.GridSession;
import org.springframework.stereotype.Service;

@Service
public class CopyReaderProviderImpl implements CopyReaderProvider {

	private final GridReplicaViewManager gridReplicaViewManager;
	private final GridReplicaSupport gridReplicaSupport;
	private final GridIndexDao gridIndexDao;
	private final GridManager gridManager;

	public CopyReaderProviderImpl(GridReplicaViewManager gridReplicaViewManager, GridReplicaSupport gridReplicaSupport,
			GridIndexDao gridIndexDao, GridManager gridManager) {
		super();
		this.gridReplicaViewManager = gridReplicaViewManager;
		this.gridReplicaSupport = gridReplicaSupport;
		this.gridIndexDao = gridIndexDao;
		this.gridManager = gridManager;
	}

	@Override
	public CopyReader createCopyReader(GridSession session) {
		return new CopyReaderImpl(gridReplicaViewManager, gridReplicaSupport, gridIndexDao, gridManager, session);
	}

}
