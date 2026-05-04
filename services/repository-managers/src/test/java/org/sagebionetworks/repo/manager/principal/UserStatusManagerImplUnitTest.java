package org.sagebionetworks.repo.manager.principal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.manager.message.MessageTemplate;
import org.sagebionetworks.repo.manager.message.PrincipalNameProvider;
import org.sagebionetworks.repo.manager.message.TemplatedMessageSender;
import org.sagebionetworks.repo.manager.oauth.OpenIDConnectManager;
import org.sagebionetworks.repo.model.AuthorizationConstants.BOOTSTRAP_PRINCIPAL;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dbo.auth.UserStatusDao;
import org.sagebionetworks.repo.model.message.MessageToUser;
import org.sagebionetworks.util.Clock;

@ExtendWith(MockitoExtension.class)
public class UserStatusManagerImplUnitTest {

	@Mock
	private UserStatusDao mockUserStatusDao;

	@Mock
	private UserManager mockUserManager;

	@Mock
	private OpenIDConnectManager mockOidcTokenManager;

	@Mock
	private Clock mockClock;

	@Mock
	private TemplatedMessageSender mockTemplatedMessageSender;

	@Mock
	private PrincipalNameProvider mockPrincipalNameProvider;

	@Spy
	@InjectMocks
	private UserStatusManagerImpl manager;

	@Test
	public void testWarnInactiveUsersWithNoUsersToWarn() {
		Instant now = Instant.now();
		when(mockClock.now()).thenReturn(Date.from(now));
		Date expectedThreshold = Date.from(now.minus(UserStatusManager.INACTIVITY_WARNING_DAYS, ChronoUnit.DAYS));
		when(mockUserStatusDao.getInactiveUsersToWarnBatch(expectedThreshold, 500)).thenReturn(Collections.emptyList());

		// call under test
		int result = manager.warnInactiveUsers(500);

		assertEquals(0, result);
		verify(mockTemplatedMessageSender, never()).sendMessage(any());
		verify(mockUserStatusDao, never()).setWarnedOn(any());
	}

	@Test
	public void testWarnInactiveUsers() {
		long userId1 = 111L;
		long userId2 = 222L;
		Instant now = Instant.now();
		when(mockClock.now()).thenReturn(Date.from(now));
		Date expectedThreshold = Date.from(now.minus(UserStatusManager.INACTIVITY_WARNING_DAYS, ChronoUnit.DAYS));
		when(mockUserStatusDao.getInactiveUsersToWarnBatch(expectedThreshold, 500))
				.thenReturn(List.of(userId1, userId2));

		UserInfo sender = new UserInfo(false);
		sender.setId(BOOTSTRAP_PRINCIPAL.DATA_ACCESS_NOTFICATIONS_SENDER.getPrincipalId());
		when(mockUserManager.getUserInfo(BOOTSTRAP_PRINCIPAL.DATA_ACCESS_NOTFICATIONS_SENDER.getPrincipalId()))
				.thenReturn(sender);

		when(mockPrincipalNameProvider.getPrincipalName(userId1)).thenReturn("Alice");
		when(mockPrincipalNameProvider.getPrincipalName(userId2)).thenReturn("Bob");
		when(mockTemplatedMessageSender.sendMessage(any())).thenReturn(new MessageToUser().setId("msg-1"));

		// call under test
		int result = manager.warnInactiveUsers(500);

		assertEquals(2, result);
		verify(mockTemplatedMessageSender, times(2)).sendMessage(any());
		verify(mockUserStatusDao).setWarnedOn(List.of(userId1, userId2));
	}

	@Test
	public void testWarnInactiveUsersWithBootstrapPrincipal() {
		long userId = 111L;
		long bootstrapId = BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId();
		Instant now = Instant.now();
		when(mockClock.now()).thenReturn(Date.from(now));
		Date expectedThreshold = Date.from(now.minus(UserStatusManager.INACTIVITY_WARNING_DAYS, ChronoUnit.DAYS));
		when(mockUserStatusDao.getInactiveUsersToWarnBatch(expectedThreshold, 500))
				.thenReturn(List.of(userId, bootstrapId));

		UserInfo sender = new UserInfo(false);
		sender.setId(BOOTSTRAP_PRINCIPAL.DATA_ACCESS_NOTFICATIONS_SENDER.getPrincipalId());
		when(mockUserManager.getUserInfo(BOOTSTRAP_PRINCIPAL.DATA_ACCESS_NOTFICATIONS_SENDER.getPrincipalId()))
				.thenReturn(sender);

		when(mockPrincipalNameProvider.getPrincipalName(userId)).thenReturn("Alice");
		when(mockTemplatedMessageSender.sendMessage(any())).thenReturn(new MessageToUser().setId("msg-1"));

		// call under test
		int result = manager.warnInactiveUsers(500);

		assertEquals(1, result);
		verify(mockTemplatedMessageSender, times(1)).sendMessage(any());
		verify(mockUserStatusDao).setWarnedOn(List.of(userId));
	}

	@Test
	public void testWarnInactiveUsersWithEmailFailure() {
		long userId1 = 111L;
		long userId2 = 222L;
		Instant now = Instant.now();
		when(mockClock.now()).thenReturn(Date.from(now));
		Date expectedThreshold = Date.from(now.minus(UserStatusManager.INACTIVITY_WARNING_DAYS, ChronoUnit.DAYS));
		when(mockUserStatusDao.getInactiveUsersToWarnBatch(expectedThreshold, 500))
				.thenReturn(List.of(userId1, userId2));

		UserInfo sender = new UserInfo(false);
		sender.setId(BOOTSTRAP_PRINCIPAL.DATA_ACCESS_NOTFICATIONS_SENDER.getPrincipalId());
		when(mockUserManager.getUserInfo(BOOTSTRAP_PRINCIPAL.DATA_ACCESS_NOTFICATIONS_SENDER.getPrincipalId()))
				.thenReturn(sender);

		doThrow(new RuntimeException("email service unavailable")).when(manager).sendInactivityWarningEmail(sender, userId1);
		when(mockPrincipalNameProvider.getPrincipalName(userId2)).thenReturn("Bob");
		when(mockTemplatedMessageSender.sendMessage(any())).thenReturn(new MessageToUser().setId("msg-1"));

		// call under test
		int result = manager.warnInactiveUsers(500);

		assertEquals(2, result);
		// userId1 email failed, userId2 email succeeded
		verify(mockTemplatedMessageSender, times(1)).sendMessage(any());
		// both users must be marked warned regardless of email failure
		verify(mockUserStatusDao).setWarnedOn(List.of(userId1, userId2));
	}

	@Test
	public void testSendInactivityWarningEmail() {
		long userId = 42L;
		UserInfo sender = new UserInfo(false);
		sender.setId(BOOTSTRAP_PRINCIPAL.DATA_ACCESS_NOTFICATIONS_SENDER.getPrincipalId());
		when(mockPrincipalNameProvider.getPrincipalName(userId)).thenReturn("Alice");
		when(mockTemplatedMessageSender.sendMessage(any())).thenReturn(new MessageToUser().setId("msg-1"));

		ArgumentCaptor<MessageTemplate> templateCaptor = ArgumentCaptor.forClass(MessageTemplate.class);

		// call under test
		manager.sendInactivityWarningEmail(sender, userId);

		verify(mockTemplatedMessageSender).sendMessage(templateCaptor.capture());
		MessageTemplate captured = templateCaptor.getValue();

		assertEquals("message/InactivityWarningNotification.html.vtl", captured.getTemplateFile());
		assertEquals(Collections.singleton("42"), captured.getRecipients());
		assertEquals(Map.of("displayName", "Alice"), captured.getContext());
		assertEquals(true, captured.ignoreNotificationSettings());
		assertEquals("Action Required: Your Synapse Account Will Be Disabled in 14 Days",
				captured.getSubject().orElse(null));
	}
}
