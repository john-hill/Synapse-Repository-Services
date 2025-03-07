package org.sagebionetworks.repo.manager.table;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.JSONArray;
import org.sagebionetworks.repo.model.entity.IdAndVersion;
import org.sagebionetworks.repo.model.table.ReplicatedEvent;
import org.sagebionetworks.repo.model.table.ReplicationType;
import org.sagebionetworks.repo.web.NotFoundException;
import org.springframework.stereotype.Service;

@Service
public class ReplicationToViewManagerImpl implements ReplicationToViewManager {

	private static final Log LOG = LogFactory.getLog(ReplicationToViewManagerImpl.class);

	private final TableIndexConnectionFactory connectionFactory;
	private final TableManagerSupport tableManagerSupport;
	private final int viewUpdateVisibilityTimeoutSeconds;

	public ReplicationToViewManagerImpl(TableIndexConnectionFactory connectionFactory,
			TableManagerSupport tableManagerSupport, int viewUpdateVisibilityTimeoutSeconds) {
		super();
		this.connectionFactory = connectionFactory;
		this.tableManagerSupport = tableManagerSupport;
		this.viewUpdateVisibilityTimeoutSeconds = viewUpdateVisibilityTimeoutSeconds;
	}

	@Override
	public void objectReplicated(ReplicatedEvent event) {

		IdAndVersion objectId = IdAndVersion.newBuilder().setId(event.getReplicatedObjectId()).build();
		ReplicationType objectType = ReplicationType.matchType(event.getReplicatedObjectType()).get();
		/*
		 * In order to cover: create, update, delete, and move actions, we need to
		 * consider the object's path before and after the change. The unique path IDs
		 * from both the before and after are used to find all views that need to be
		 * updated as a result of the changed.
		 */
		List<Long> beforePath = parseJSONArray(event.getBeforePathIds());
		List<Long> afterPath = parseJSONArray(event.getAfterPathIds());
		Set<Long> allIds = new HashSet<>(beforePath.size() + afterPath.size());
		allIds.addAll(beforePath);
		allIds.addAll(afterPath);

		TableIndexManager indexManger = connectionFactory.connectToTableIndex(objectId);
		indexManger.getViewsIntersectionForPath(allIds, objectType).forEachRemaining(viewId -> {
			LOG.info(String.format("View: syn%d matched for event: %s", viewId, event));
			indexManger.setViewAsNeedsUpdate(viewId, viewUpdateVisibilityTimeoutSeconds);
		});
	}

	/**
	 * Helper to extract a list of longs from the provided JSON array string.
	 * 
	 * @param json
	 * @return
	 */
	static List<Long> parseJSONArray(String json) {
		if (json == null) {
			return Collections.emptyList();
		}
		JSONArray array = new JSONArray(json);
		List<Long> results = new ArrayList<>(array.length());
		for (int i = 0; i < array.length(); i++) {
			results.add(array.getLong(i));
		}
		return results;
	}

	@Override
	public void consumeVisibleViewUpdates() {
		TableIndexManager indexManger = connectionFactory.connectToFirstIndex();
		while (indexManger.consumeFirstVisibleViewUpdate(viewId -> {
			LOG.info(String.format("Triggering an update for view: syn%d", viewId));
			try {
				tableManagerSupport.triggerIndexUpdate(IdAndVersion.newBuilder().setId(viewId).build());
			} catch (NotFoundException e) {
				// skip views that are not found.
			}catch(Throwable e) {
				LOG.error("Failed to trigger view update:", e);
			}
		})) {
		}
	}

}
