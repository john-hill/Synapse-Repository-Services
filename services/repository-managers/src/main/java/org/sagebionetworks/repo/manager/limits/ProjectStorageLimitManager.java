package org.sagebionetworks.repo.manager.limits;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.sagebionetworks.StackConfiguration;
import org.sagebionetworks.repo.model.AuthorizationConstants.BOOTSTRAP_PRINCIPAL;
import org.sagebionetworks.repo.model.EntityType;
import org.sagebionetworks.repo.model.NodeDAO;
import org.sagebionetworks.repo.model.ObjectType;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dbo.dao.DBOStorageLocationDAOImpl;
import org.sagebionetworks.repo.model.dbo.limits.ProjectStorageLimitsDao;
import org.sagebionetworks.repo.model.jdo.KeyFactory;
import org.sagebionetworks.repo.model.limits.ProjectStorageData;
import org.sagebionetworks.repo.model.limits.ProjectStorageEvent;
import org.sagebionetworks.repo.model.limits.ProjectStorageLimitsBackfillRequest;
import org.sagebionetworks.repo.model.limits.ProjectStorageLimitsBackfillResponse;
import org.sagebionetworks.repo.model.limits.ProjectStorageLocationLimit;
import org.sagebionetworks.repo.model.limits.ProjectStorageLocationUsage;
import org.sagebionetworks.repo.model.limits.ProjectStorageUsage;
import org.sagebionetworks.repo.model.message.TransactionalMessenger;
import org.sagebionetworks.repo.transactions.WriteTransaction;
import org.sagebionetworks.table.cluster.TableIndexDAO;
import org.sagebionetworks.util.Clock;
import org.sagebionetworks.util.Pair;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ProjectStorageLimitManager {
	
	private static final Logger LOGGER = LogManager.getLogger(ProjectStorageLimitManager.class);
	
	public static final String DEFAULT_STORAGE_LOCATION_ID = DBOStorageLocationDAOImpl.DEFAULT_STORAGE_LOCATION_ID.toString();
	
	private static final Duration CACHE_UPDATE_FREQUENCY = Duration.ofMinutes(2);
	
	private TransactionalMessenger messenger;
	
	private ProjectStorageLimitsDao storageUsageDao;
	
	private TableIndexDAO replicationDao;
	
	private NodeDAO nodeDao;
	
	private Clock clock;
	
	private Set<Long> accessedProjects;
	
	private Long defaultStorageLocationMaxBytes;
	
	public ProjectStorageLimitManager(TransactionalMessenger messenger, ProjectStorageLimitsDao storageUsageDao, TableIndexDAO replicationDao, NodeDAO nodeDao, Clock clock) {
		this.messenger = messenger;
		this.storageUsageDao = storageUsageDao;
		this.replicationDao = replicationDao;
		this.nodeDao = nodeDao;
		this.clock = clock;
		this.accessedProjects = ConcurrentHashMap.newKeySet();
	}
	
	@Autowired
	void setDefaultStorageLocationMaxBytes(StackConfiguration config) {
		this.defaultStorageLocationMaxBytes = config.getDefaultProjectStorageLimit();
	}
	
	public ProjectStorageUsage gerProjectStorageUsage(String projectId) {
		Long projectIdLong = validateAndGetProjectId(projectId);
		
		// First the the usage map
		Map<String, Long> storageUsage = storageUsageDao.getStorageData(projectIdLong)
				.map(ProjectStorageData::getStorageLocationData)
				.orElseGet(() -> Collections.emptyMap());
		
		// Now fill the usage data together with the limit, we only return data if we have a limit
		List<ProjectStorageLocationUsage> locations = storageUsageDao.getStorageLocationLimits(projectIdLong).stream()
			.map(limit -> {
				Long currentUsage = storageUsage.getOrDefault(limit.getStorageLocationId(), 0L);
				
				return new ProjectStorageLocationUsage()
					.setStorageLocationId(limit.getStorageLocationId())
					.setMaxAllowedFileBytes(limit.getMaxAllowedFileBytes())
					.setSumFileBytes(currentUsage)
					.setIsOverLimit(currentUsage > limit.getMaxAllowedFileBytes());
			})
			.collect(Collectors.toList());
		
		accessedProjects.add(projectIdLong);
						
		return new ProjectStorageUsage()
			.setProjectId(KeyFactory.keyToString(projectIdLong))
			.setLocations(locations);
	}
	
	/**
	 * Sets a default storage location limit if a limit for the given project/storage location combination if a limit doesn't exist yet.
	 * 
	 * @param projectId
	 * @param storageLocationId
	 */
	@WriteTransaction
	public void setDefaultProjectStorageLimit(String projectId, String storageLocationId) {
		ValidateArgument.required(storageLocationId, "The storage location id");
		
		// If a limit is already in place we do not change it
		if (storageUsageDao.getStorageLocationLimit(validateAndGetProjectId(projectId), Long.valueOf(storageLocationId)).isPresent()) {
			return;
		}
		
		Long maxAllowedBytes = DEFAULT_STORAGE_LOCATION_ID.equals(storageLocationId) ? defaultStorageLocationMaxBytes : null;
		
		ProjectStorageLocationLimit limit = new ProjectStorageLocationLimit()
			.setProjectId(projectId)
			.setStorageLocationId(storageLocationId)
			.setMaxAllowedFileBytes(maxAllowedBytes);
		
		storageUsageDao.setStorageLocationLimit(BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId(), limit);
	}
	
	@WriteTransaction
	public void refreshProjectStorageData(Long projectId) {
		if (storageUsageDao.isStorageDataModifiedOnAfter(projectId, clock.now().toInstant().minus(CACHE_UPDATE_FREQUENCY))) {
			return;
		}
		
		ProjectStorageData data = replicationDao.computeProjectStorageData(projectId);
	
		storageUsageDao.setStorageData(List.of(data));
	}
	
	public ProjectStorageLimitsBackfillResponse backfillProjectLimits(UserInfo user, ProjectStorageLimitsBackfillRequest request) {
		if (!user.isAdmin()) {
        	throw new UnauthorizedException("Only an administrator may access this service.");
        }

		long batchSize = request.getBatchSize();
		long limit = batchSize;
		long offset = 0;
		
		List<Long> projectBatch = new ArrayList<>((int) batchSize);
		long totalNewLimits = 0;
		
		while (!(projectBatch = storageUsageDao.getProjectIdsBatch(limit, offset)).isEmpty()) {
			
			LOGGER.info("Computing storage locations for {} projects...", projectBatch.size());
			
			Set<Pair<Long, Long>> storageLocationPairs = new HashSet<>(replicationDao.getProjectStorageLocations(projectBatch));
			
			// Make sure the default storage location is in there for each project
			storageLocationPairs.addAll(projectBatch.stream()
				.map(projectId -> Pair.create(projectId, DBOStorageLocationDAOImpl.DEFAULT_STORAGE_LOCATION_ID))
				.collect(Collectors.toSet())
			);
			
			LOGGER.info("Computing storage locations for {} projects...DONE (Total: {})", projectBatch.size(), storageLocationPairs.size());
			
			LOGGER.info("Persisting {} limits...", storageLocationPairs.size());
			int updatedCount = storageUsageDao.setNullLimitBatch(user.getId(), storageLocationPairs);
			LOGGER.info("Persisting {} limits...DONE (Acutal Count: {})", storageLocationPairs.size(), updatedCount);
			
			totalNewLimits += updatedCount;
			
			if (projectBatch.size() < batchSize) {
				break;
			}
			
			offset += batchSize;
		}
		
		return new ProjectStorageLimitsBackfillResponse().setLimitsAddedCount(totalNewLimits);
	}
	
	// On a timer this is invoked to send the notifications for each accessed project
	public void sendProjectStorageNotifications() {
		accessedProjects.forEach( projectId -> {
			messenger.publishMessageAfterCommit(new ProjectStorageEvent()
				.setObjectType(ObjectType.PROJECT_STORAGE_EVENT)
				.setObjectId(projectId.toString())
				.setProjectId(projectId)
			);
			accessedProjects.remove(projectId);
		});
	}
	
	Long validateAndGetProjectId(String projectId) {
		ValidateArgument.requiredNotBlank(projectId, "The projectId");
		ValidateArgument.requirement(EntityType.project.equals(nodeDao.getNodeTypeById(projectId)), "The entity with the given id is not a project.");
		
		return KeyFactory.stringToKey(projectId);
	}

}
