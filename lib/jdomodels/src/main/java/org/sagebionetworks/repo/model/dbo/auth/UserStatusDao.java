package org.sagebionetworks.repo.model.dbo.auth;

import org.sagebionetworks.repo.transactions.WriteTransaction;

import java.util.Date;
import java.util.List;
import java.util.Optional;

public interface UserStatusDao {

	void setLastSeenOn(List<Long> principalIds, Date lastSeenOn);
	
	Optional<Date> getLastSeenOn(long principalId);
	
	void setDisabled(long principalId, boolean disabled);

	void resetStatusToEnabled(long principalId);

	boolean isDisabled(long principalId);
	
	List<Long> getInactiveUsersBatch(Date lastSeenOnThreshold, int batchSize);

	/**
	 * Returns up to batchSize principal IDs for users who are not disabled,
	 * have been inactive since before lastSeenOnThreshold, and have not yet been warned.
	 */
	List<Long> getInactiveUsersToWarnBatch(Date lastSeenOnThreshold, int batchSize);

	/**
	 * Records that a warning was sent to each user in the list by setting DISABLE_WARNING_SENT_ON = NOW(3).
	 */
	void setWarnedOn(List<Long> principalIds);
}

