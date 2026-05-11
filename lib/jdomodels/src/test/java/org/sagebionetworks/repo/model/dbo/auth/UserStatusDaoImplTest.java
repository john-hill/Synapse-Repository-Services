package org.sagebionetworks.repo.model.dbo.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.UserGroup;
import org.sagebionetworks.repo.model.UserGroupDAO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = { "classpath:jdomodels-test-context.xml" })
public class UserStatusDaoImplTest {

	@Autowired
	private UserGroupDAO userGroupDAO;

	@Autowired
	private UserStatusDao userStatusDao;

	private Long userId;

	@BeforeEach
	public void setUp() {
		userId = userGroupDAO.create(new UserGroup().setIsIndividual(true).setRealmId(AuthorizationConstants.DEFAULT_REALM_ID));
	}

	@AfterEach
	public void tearDown() {
		userGroupDAO.delete(userId.toString());
	}

	@Test
	public void testGetAndSetLastSeenOn() {
		assertEquals(Optional.empty(), userStatusDao.getLastSeenOn(userId));

		Date lastSeenOn = Date.from(Instant.now().minus(1, ChronoUnit.DAYS));

		userStatusDao.setLastSeenOn(List.of(userId), lastSeenOn);

		assertEquals(Optional.of(lastSeenOn), userStatusDao.getLastSeenOn(userId));

		lastSeenOn = Date.from(Instant.now().plus(1, ChronoUnit.DAYS));

		userStatusDao.setLastSeenOn(List.of(userId), lastSeenOn);

		assertEquals(Optional.of(lastSeenOn), userStatusDao.getLastSeenOn(userId));
	}

	@Test
	public void testGetAndSetDisabled() {
		assertFalse(userStatusDao.isDisabled(userId));

		userStatusDao.setDisabled(userId, true);

		assertTrue(userStatusDao.isDisabled(userId));

		userStatusDao.setDisabled(userId, false);

		assertFalse(userStatusDao.isDisabled(userId));
	}

	@Test
	public void testResetStatusToEnabled() throws InterruptedException {
		Instant instantNow = Instant.now();
		// set realistic disabled status (last seen more than 180 days ago and set disabled by worker)
		Date lastSeenOn = Date.from(instantNow.minus(181, ChronoUnit.DAYS));
		userStatusDao.setLastSeenOn(List.of(userId), lastSeenOn);
		userStatusDao.setDisabled(userId, true);

		assertTrue(userStatusDao.isDisabled(userId));
		assertEquals(lastSeenOn, userStatusDao.getLastSeenOn(userId).orElseThrow());

		Thread.sleep(2000);

		// call under test
		userStatusDao.resetStatusToEnabled(userId);

		assertFalse(userStatusDao.isDisabled(userId));
		Date updatedLastSeenOn = userStatusDao.getLastSeenOn(userId).orElseThrow();
		assertTrue(updatedLastSeenOn.after(lastSeenOn), "LAST_SEEN_ON should be updated from the original stale value");
		// assertTrue(d.compareTo(Duration.ofDays(1)) < 0, "updatedLastSeenOn should be within 1 day of instantNow");
	}
	
	@Test
	public void testGetInactiveUsersBatch() {
		Date lastSeenOnThreshold = Date.from(Instant.now().minus(30, ChronoUnit.DAYS));

		int batchSize = 10;

		// Call under test: users that have no last seen date should not be considered inactive
		assertTrue(userStatusDao.getInactiveUsersBatch(lastSeenOnThreshold, batchSize).isEmpty());

		// Set the user as active by setting last seen within the threshold
		userStatusDao.setLastSeenOn(List.of(userId), Date.from(Instant.now().minus(30, ChronoUnit.DAYS)));

		// Call under test
		assertTrue(userStatusDao.getInactiveUsersBatch(lastSeenOnThreshold, batchSize).isEmpty());

		// Set the user as inactive
		userStatusDao.setLastSeenOn(List.of(userId), Date.from(Instant.now().minus(31, ChronoUnit.DAYS)));

		// Now we should find the user in the inactive list
		assertEquals(List.of(userId), userStatusDao.getInactiveUsersBatch(lastSeenOnThreshold, batchSize));
	}

	@Test
	public void testGetInactiveUsersToWarnBatchWithNoLastSeenOn() {
		Date warningThreshold = Date.from(Instant.now().minus(356, ChronoUnit.DAYS));

		// Call under test: users with no last seen date should not appear in the warn batch
		assertTrue(userStatusDao.getInactiveUsersToWarnBatch(warningThreshold, 10).isEmpty());
	}

	@Test
	public void testGetInactiveUsersToWarnBatchWithRecentActivity() {
		Date warningThreshold = Date.from(Instant.now().minus(356, ChronoUnit.DAYS));
		userStatusDao.setLastSeenOn(List.of(userId), Date.from(Instant.now().minus(10, ChronoUnit.DAYS)));

		// Call under test: recently active user should not appear in the warn batch
		assertTrue(userStatusDao.getInactiveUsersToWarnBatch(warningThreshold, 10).isEmpty());
	}

	@Test
	public void testGetInactiveUsersToWarnBatchWithInactiveUser() {
		Date warningThreshold = Date.from(Instant.now().minus(356, ChronoUnit.DAYS));
		userStatusDao.setLastSeenOn(List.of(userId), Date.from(Instant.now().minus(357, ChronoUnit.DAYS)));

		// Call under test: user inactive longer than the threshold should be in the warn batch
		assertEquals(List.of(userId), userStatusDao.getInactiveUsersToWarnBatch(warningThreshold, 10));

		// Call under test: after setting warned, the user should no longer appear
		userStatusDao.setDisableWarningSentOn(List.of(userId));
		assertTrue(userStatusDao.getInactiveUsersToWarnBatch(warningThreshold, 10).isEmpty());
	}

	@Test
	public void testSetLastSeenOnClearsWarnedOn() {
		Date warningThreshold = Date.from(Instant.now().minus(356, ChronoUnit.DAYS));
		userStatusDao.setLastSeenOn(List.of(userId), Date.from(Instant.now().minus(357, ChronoUnit.DAYS)));
		userStatusDao.setDisableWarningSentOn(List.of(userId));

		// User has been warned — no longer in the warn batch
		assertTrue(userStatusDao.getInactiveUsersToWarnBatch(warningThreshold, 10).isEmpty());

		// Call under test: setLastSeenOn resets DISABLE_WARNING_SENT_ON to null
		userStatusDao.setLastSeenOn(List.of(userId), Date.from(Instant.now().minus(10, ChronoUnit.DAYS)));

		// User is now recently active — still not in the warn batch (LAST_SEEN_ON is recent)
		assertTrue(userStatusDao.getInactiveUsersToWarnBatch(warningThreshold, 10).isEmpty());

		// Backdate LAST_SEEN_ON again — DISABLE_WARNING_SENT_ON was cleared, so user reappears in warn batch
		userStatusDao.setLastSeenOn(List.of(userId), Date.from(Instant.now().minus(357, ChronoUnit.DAYS)));
		assertEquals(List.of(userId), userStatusDao.getInactiveUsersToWarnBatch(warningThreshold, 10));
	}
}
