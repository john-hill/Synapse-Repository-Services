package org.sagebionetworks.repo.manager.grid.internal.replica;

import org.sagebionetworks.repo.model.grid.GridConnectionInfo;
import org.sagebionetworks.repo.model.grid.patch.Patch;
import org.sagebionetworks.util.progress.ProgressCallback;

public interface GirdReplicaManager {

	public static final String SYNCHRONIZE_CLOCK = "synchronize-clock";

	void onConnected(ProgressCallback callback, GridConnectionInfo connection);

	void onNewPatch(ProgressCallback callback, GridConnectionInfo connection);

	void onApplyPatch(ProgressCallback callback, GridConnectionInfo connection, Integer messageId, Patch patch);

	void onResponseComplete(GridConnectionInfo connection, Integer methodId);

}
