package org.sagebionetworks.repo.manager.limits;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.sagebionetworks.StackConfiguration;
import org.sagebionetworks.downloadtools.FileUtils;
import org.sagebionetworks.repo.manager.entity.EntityAuthorizationManager;
import org.sagebionetworks.repo.manager.feature.FeatureManager;
import org.sagebionetworks.repo.model.ACCESS_TYPE;
import org.sagebionetworks.repo.model.AuthorizationConstants.BOOTSTRAP_PRINCIPAL;
import org.sagebionetworks.repo.model.AuthorizationUtils;
import org.sagebionetworks.repo.model.EntityType;
import org.sagebionetworks.repo.model.NodeDAO;
import org.sagebionetworks.repo.model.ObjectType;
import org.sagebionetworks.repo.model.StorageLocationDAO;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dbo.limits.ProjectStorageLimitsDao;
import org.sagebionetworks.repo.model.feature.Feature;
import org.sagebionetworks.repo.model.jdo.KeyFactory;
import org.sagebionetworks.repo.model.limits.ProjectStorageData;
import org.sagebionetworks.repo.model.limits.ProjectStorageEvent;
import org.sagebionetworks.repo.model.limits.ProjectStorageLocationLimit;
import org.sagebionetworks.repo.model.limits.ProjectStorageLocationUsage;
import org.sagebionetworks.repo.model.limits.ProjectStorageUsage;
import org.sagebionetworks.repo.model.message.TransactionalMessenger;
import org.sagebionetworks.repo.transactions.WriteTransaction;
import org.sagebionetworks.repo.web.NotFoundException;
import org.sagebionetworks.repo.web.ProjectStorageLimitExceededException;
import org.sagebionetworks.table.cluster.TableIndexDAO;
import org.sagebionetworks.util.Clock;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ProjectStorageLimitsManager {
	
	private static final Logger LOGGER = LogManager.getLogger(ProjectStorageLimitsManager.class);
	
	static final Duration CACHE_UPDATE_FREQUENCY = Duration.ofMinutes(1);
	
	static ProjectStorageLocationUsage mapStorageLocationUsage(ProjectStorageLocationLimit limit, Long currentUsage) {
		return new ProjectStorageLocationUsage()
			.setStorageLocationId(limit.getStorageLocationId())
			.setMaxAllowedFileBytes(limit.getMaxAllowedFileBytes())
			.setSumFileBytes(currentUsage)
			.setIsOverLimit(limit.getMaxAllowedFileBytes() == null ? false : currentUsage > limit.getMaxAllowedFileBytes());
	}
	
	private EntityAuthorizationManager authzManager;
	
	private TransactionalMessenger messenger;
	
	private ProjectStorageLimitsDao storageUsageDao;

	private TableIndexDAO replicationDao;
	
	private NodeDAO nodeDao;
	
	private StorageLocationDAO storageLocationDao;
	
	private Clock clock;
	
	private FeatureManager featureManager;
	
	private Set<Long> accessedProjects;
	
	private Long defaultStorageLocationMaxBytes;
	
	public ProjectStorageLimitsManager(EntityAuthorizationManager authzManager, TransactionalMessenger messenger, ProjectStorageLimitsDao storageUsageDao,
		TableIndexDAO replicationDao, NodeDAO nodeDao, StorageLocationDAO storageLocationDao, Clock clock, FeatureManager featureManager) {
		this.authzManager = authzManager;
		this.messenger = messenger;
		this.storageUsageDao = storageUsageDao;
		this.replicationDao = replicationDao;
		this.nodeDao = nodeDao;
		this.storageLocationDao = storageLocationDao;
		this.clock = clock;
		this.featureManager = featureManager;
		this.accessedProjects = ConcurrentHashMap.newKeySet();
	}
	
	@Autowired
	void setDefaultStorageLocationMaxBytes(StackConfiguration config) {
		this.defaultStorageLocationMaxBytes = config.getDefaultProjectStorageLimit();
	}
	
	/**
	 * @param projectId
	 * @return The project usage data for the project with the given id, only the data for storage locations with a set limit will be included
	 */
	public ProjectStorageUsage getProjectStorageUsage(UserInfo user, String projectId) {
		ValidateArgument.required(user, "The user");
		Long projectIdLong = validateAndGetProjectId(projectId);
		
		if (!AuthorizationUtils.isPlanManagerOrAdmin(user)) {
			authzManager.hasAccess(user, projectId, ACCESS_TYPE.CREATE).checkAuthorizationOrElseThrow();
		}
		
		ProjectStorageUsage usage = getProjectStorageUsage(projectIdLong);
		
		accessedProjects.add(projectIdLong);
						
		return usage;
	}
	
	/**
	 * Internal use only. Does not perform validation on the project
	 * 
	 * @param projectId
	 * @return The project usage data for the project with the given id, only the data for storage locations with a set limit will be included
	 */
	public ProjectStorageUsage getProjectStorageUsage(Long projectId) {
		// First the the usage map
		Map<String, Long> storageUsage = storageUsageDao.getStorageData(projectId)
				.map(ProjectStorageData::getStorageLocationData)
				.orElseGet(() -> Collections.emptyMap());
		
		// Now fill the usage data together with the limit, we only return data if we have a limit
		List<ProjectStorageLocationUsage> locations = storageUsageDao.getStorageLocationLimits(projectId).stream()
			.map(limit -> mapStorageLocationUsage(limit, storageUsage.getOrDefault(limit.getStorageLocationId().toString(), 0L)))
			.collect(Collectors.toList());
		
		return new ProjectStorageUsage()
			.setProjectId(KeyFactory.keyToString(projectId))
			.setLocations(locations);
	}
	
	/**
	 * @param projectId
	 * @param storageLocationId
	 * @return Internal usage only (no authz check): The project storage location usage for the given project and storage location combination if a limit exists.
	 */
	public Optional<ProjectStorageLocationUsage> getProjectStorageLocationUsage(String projectId, Long storageLocationId) {
		ValidateArgument.required(storageLocationId, "The storageLocationId");
		
		Long projectIdLong = validateAndGetProjectId(projectId);
		
		accessedProjects.add(projectIdLong);
		
		return storageUsageDao.getStorageLocationLimit(projectIdLong, storageLocationId).map(limit -> {			
			Long currentUsage = storageUsageDao.getStorageData(projectIdLong)
				.map(storageData -> storageData.getStorageLocationData().get(storageLocationId.toString()))
				.orElse(0L);			
			return mapStorageLocationUsage(limit, currentUsage);
		});
	}
	
	/**
	 * Sets the given limit for a project/storage location pair, only a member of the plan managers team (See {@link BOOTSTRAP_PRINCIPAL#PLAN_MANAGERS} or an admin can perform this operation
	 * 
	 * @param userInfo
	 * @param limit
	 * @return
	 */
	@WriteTransaction
	public ProjectStorageLocationLimit setProjectStorageLimit(UserInfo userInfo, ProjectStorageLocationLimit limit) {
		ValidateArgument.required(userInfo, "The user");
		ValidateArgument.required(limit, "The limit");
		ValidateArgument.requirement(limit.getMaxAllowedFileBytes() == null || limit.getMaxAllowedFileBytes() >= 0, "The maxAllowedFileBytes cannot be a negative number.");
		
		if (!AuthorizationUtils.isPlanManagerOrAdmin(userInfo)) {
			throw new UnauthorizedException("You are not authorized to perform this operation.");
		}
		
		validateStorageLocationId(limit.getStorageLocationId());
		
		accessedProjects.add(validateAndGetProjectId(limit.getProjectId()));
				
		return storageUsageDao.setStorageLocationLimit(userInfo.getId(), limit);
	}
	
	/**
	 * Sets a default storage location limit for the given project/storage location combination if a limit doesn't exist yet.
	 * 
	 * @param projectId
	 * @param storageLocationId
	 */
	@WriteTransaction
	public void setDefaultProjectStorageLimit(String projectId, Long storageLocationId) {
		validateStorageLocationId(storageLocationId);
		Long projectIdLong = validateAndGetProjectId(projectId);
		
		// If a limit is already in place we do not change it
		if (storageUsageDao.getStorageLocationLimit(projectIdLong, storageLocationId).isPresent()) {
			return;
		}
		
		Long maxAllowedBytes = StorageLocationDAO.DEFAULT_STORAGE_LOCATION_ID.equals(storageLocationId) ? defaultStorageLocationMaxBytes : null;
		
		ProjectStorageLocationLimit limit = new ProjectStorageLocationLimit()
			.setProjectId(projectId)
			.setStorageLocationId(storageLocationId)
			.setMaxAllowedFileBytes(maxAllowedBytes);
		
		storageUsageDao.setStorageLocationLimit(BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId(), limit);
	}
	
	/**
	 * Verifies that the current usage of the storage location with the given id for the given project is under the set limit
	 * 
	 * @param projectId
	 * @param storageLocationId
	 * @throws ProjectStorageLimitExceededException If the current storage usage for the storage location in the project exceeds the set limit
	 * @throws
	 *  
	 */
	public void verifyProjectStorageLocationUsageUnderLimit(String projectId, Long storageLocationId) {
		if (!featureManager.isFeatureEnabled(Feature.ENFORCE_PROJECT_STORAGE_LIMITS)) {
			return;
		}
		
		getProjectStorageLocationUsage(projectId, storageLocationId).ifPresentOrElse(usage -> {
			if (usage.getIsOverLimit()) {
				throw new ProjectStorageLimitExceededException(
					String.format("The project storage usage exceeds the limit for the storage location (Project: %s, Storage Location: %s, Usage: %s, Limit: %s).", projectId,
						storageLocationId, FileUtils.bytesToHumanReadable(usage.getSumFileBytes()), FileUtils.bytesToHumanReadable(usage.getMaxAllowedFileBytes())));
			}
		}, () -> {
			// See https://sagebionetworks.jira.com/browse/PLFM-8731. There are existing pipelines that would break if we throw an exception when a limit is not there.
			// Since this require a change in the process for external collaborators we simply log in this case
			LOGGER.warn("The storage location {} is not assigned to the project {}.", storageLocationId, projectId);
		});
	}
	
	/**
	 * Recomputes the project usage data from the object replication for the project with the given id if expired
	 * 
	 * @param projectId
	 */
	@WriteTransaction
	public void refreshProjectStorageData(Long projectId) {
		if (storageUsageDao.isStorageDataModifiedOnAfter(projectId, clock.now().toInstant().minus(CACHE_UPDATE_FREQUENCY))) {
			return;
		}
		
		ProjectStorageData data = replicationDao.computeProjectStorageData(projectId);
	
		storageUsageDao.setStorageData(List.of(data));
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
	
	void validateStorageLocationId(Long storageLocationId) {
		ValidateArgument.required(storageLocationId, "The storageLocationId");
		
		if (!storageLocationDao.exists(storageLocationId)) {
			throw new NotFoundException("A storage location with id " + storageLocationId + " does not exist.");
		}
	}

}
