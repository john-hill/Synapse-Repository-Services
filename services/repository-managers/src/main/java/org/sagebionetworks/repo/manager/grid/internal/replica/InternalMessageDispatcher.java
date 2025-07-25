package org.sagebionetworks.repo.manager.grid.internal.replica;

import java.util.Optional;

import org.json.JSONArray;
import org.sagebionetworks.grid.db.GridIndexManager;
import org.sagebionetworks.grid.db.MessageChain;
import org.sagebionetworks.repo.model.grid.patch.Patch;
import org.sagebionetworks.repo.model.grid.patch.compact.PatchCompactSerializable;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.stereotype.Component;

@Component
public class InternalMessageDispatcher {

	private final GirdReplicaManager girdReplicaManager;
	private final GridIndexManager gridIndexManager;

	public InternalMessageDispatcher(GirdReplicaManager girdReplicaManager, GridIndexManager gridIndexManager) {
		super();
		this.girdReplicaManager = girdReplicaManager;
		this.gridIndexManager = gridIndexManager;
	}

	public void dispatchMessage(JsonRxMessageBundle bundle) {
		ValidateArgument.required(bundle, "bundle");
		ValidateArgument.required(bundle.getConnection(), "bundle.connection");
		ValidateArgument.required(bundle.getMessage(), "bundle.messgae");
		ValidateArgument.required(bundle.getMessage(), "bundle.messgae");
		ValidateArgument.required(bundle.getProgressCallback(), "bundle.callback");

		switch (bundle.getMessage().getType()) {
		case Notification:
			String method = bundle.getMessage().getMethod().get();
			if ("connected".equals(method)) {
				girdReplicaManager.onConnected(bundle.getProgressCallback(), bundle.getConnection());
				break;
			} else if ("new-patch".equals(method)) {
				girdReplicaManager.onNewPatch(bundle.getProgressCallback(), bundle.getConnection());
				break;
			}
		case ResponseData:
			if (bundle.getMessage().getId().isEmpty()) {
				throw new IllegalArgumentException("ResponseData must have an ID.");
			}
			Optional<MessageChain> opChain = gridIndexManager.getMessageChain(bundle.getConnection().getSessionId(),
					bundle.getConnection().getReplicaId(), bundle.getMessage().getId().get());
			if (GirdReplicaManager.SYNCHRONIZE_CLOCK.equals(opChain.get().getMethod())) {
				Patch patch = PatchCompactSerializable.deserialize((JSONArray) bundle.getMessage().getBody().get());
				girdReplicaManager.onApplyPatch(bundle.getProgressCallback(), bundle.getConnection(),
						bundle.getMessage().getId().get(), patch);
				break;
			}
		case ResponseComplete:
			girdReplicaManager.onResponseComplete(bundle.getConnection(), bundle.getMessage().getId().get());
			break;
		default:
			throw new IllegalArgumentException(String.format("Cannot handle: '%s'", bundle.getMessage().toJson()));
		}
	}
}
