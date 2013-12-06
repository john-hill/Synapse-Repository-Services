package org.sagebionetworks.repo.web.service;

import java.util.List;

import org.sagebionetworks.repo.manager.StorageUsageManager;
import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.model.DatastoreException;
import org.sagebionetworks.repo.model.PaginatedResults;
import org.sagebionetworks.repo.model.QueryResults;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.storage.StorageUsage;
import org.sagebionetworks.repo.model.storage.StorageUsageDimension;
import org.sagebionetworks.repo.model.storage.StorageUsageSummaryList;
import org.sagebionetworks.repo.web.NotFoundException;
import org.springframework.beans.factory.annotation.Autowired;

public class StorageUsageServiceImpl implements StorageUsageService {

	@Autowired
	private UserManager userManager;

	@Autowired
	private StorageUsageManager storageUsageManager;

	@Override
	public StorageUsageSummaryList getUsage(Long currUserName,
			List<StorageUsageDimension> dimensionList) throws UnauthorizedException, DatastoreException {

		if (currUserName == null) {
			throw new IllegalArgumentException("Current user name cannot be null or empty.");
		}

		boolean isAdmin = isAdmin(currUserName);
		if (!isAdmin) {
			throw new UnauthorizedException("Only administrators are allowed.");
		}

		validateDimensionList(dimensionList);

		StorageUsageSummaryList results = storageUsageManager.getUsage(dimensionList);
		return results;
	}

	@Override
	public StorageUsageSummaryList getUsageForUser(Long currUserName, Long userId,
			List<StorageUsageDimension> dimensionList)
			throws UnauthorizedException, NotFoundException, DatastoreException {

		if (currUserName == null) {
			throw new IllegalArgumentException("Current user name cannot be null or empty.");
		}
		if (userId == null) {
			throw new IllegalArgumentException("User ID cannot be null or empty.");
		}

		boolean isAdmin = isAdmin(currUserName);
		checkAuthorization(isAdmin, currUserName, userId);
		validateDimensionList(dimensionList);

		StorageUsageSummaryList results = storageUsageManager.getUsageForUser(userId, dimensionList);
		return results;
	}

	@Override
	public StorageUsageSummaryList getUsageByUserInRange(Long currUserName,
			Integer offset, Integer limit) throws UnauthorizedException, DatastoreException {

		if (currUserName == null) {
			throw new IllegalArgumentException("Current user name cannot be null or empty.");
		}

		boolean isAdmin = isAdmin(currUserName);
		if (!isAdmin) {
			throw new UnauthorizedException("Only administrators are allowed.");
		}

		StorageUsageSummaryList results = storageUsageManager.getUsageByUserInRange(offset, limit);
		return results;
	}

	@Override
	public PaginatedResults<StorageUsage> getUsageInRangeForUser(Long currUserId, Long userId,
			Integer offset, Integer limit, String urlPath)
			throws UnauthorizedException, NotFoundException, DatastoreException {

		if (currUserId == null) {
			throw new IllegalArgumentException("Current user name cannot be null or empty.");
		}
		if (userId == null) {
			throw new IllegalArgumentException("User name cannot be null or empty.");
		}

		boolean isAdmin = isAdmin(currUserId);
		checkAuthorization(isAdmin, currUserId, userId);
		QueryResults<StorageUsage> queryResults = storageUsageManager.getUsageInRangeForUser(userId, offset, limit);
		PaginatedResults<StorageUsage> results = new PaginatedResults<StorageUsage>(urlPath, 
				queryResults.getResults(), queryResults.getTotalNumberOfResults(), 
				offset, limit, null, true);
		return results;
	}

	/**
	 * Whether the current user is an administrator.
	 */
	private boolean isAdmin(Long currUserName) {
		UserInfo currUserInfo = null;
		try {
			currUserInfo = userManager.getUserInfo(currUserName);
		} catch (NotFoundException e) {
			return false;
		}
		if (currUserInfo != null && currUserInfo.isAdmin()) {
			return true;
		}
		return false;
	}

	/**
	 * Whether the current user is allowed to view storage usage for the specified user.
	 *
	 * @throws UnauthorizedException When current user is not authorized to view another user
	 */
	private void checkAuthorization(boolean isAdmin, Long currUserName, Long userName)
			throws DatastoreException, UnauthorizedException {
		if (!currUserName.equals(userName)) {
			if (!isAdmin) {
				throw new UnauthorizedException(
						"Only administrator is allowed to view other user's storage usage.");
			}
		}
	}

	/**
	 * Some aggregating dimensions are only accessible via paginated views.
	 *
	 * @throws IllegalArgumentException When an aggregating dimension is for paginated views only.
	 */
	private void validateDimensionList(List<StorageUsageDimension> dimensionList) {
		for (StorageUsageDimension d : dimensionList) {
			if (StorageUsageDimension.USER_ID.equals(d)) {
				throw new IllegalArgumentException(StorageUsageDimension.USER_ID + " is for paginated views only.");
			}
		}
	}

}
