package org.sagebionetworks.repo.manager.table;

import org.sagebionetworks.repo.model.table.ReplicatedEvent;

public interface ReplicationToViewManager {

	void objectReplicated(ReplicatedEvent event);

}
