package org.sagebionetworks.repo.manager.grid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.manager.entity.EntityAuthorizationManager;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserGroupDAO;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dbo.grid.GridDao;

@ExtendWith(MockitoExtension.class)
public class GridAuthorizationManagerImplTest {

	@Mock
	GridDao mockGridDao;
	@Mock
	EntityAuthorizationManager mockEntityAuthorizationManager;
	@Mock
	UserGroupDAO mockUserGroupDAO;
	@InjectMocks
	GridAuthorizationManagerImpl manager;

	UserInfo user;

	@BeforeEach
	void setup() {
		user = new UserInfo(false, 111L);
		user.setGroups(Set.of(111L, 222L));
	}

	@Test
	public void testValidateGridOwnerWithNullOwner() {
		// call under test
		Long result = manager.validateGridOwner(user, null);

		assertEquals(user.getId(), result);
		verifyNoMoreInteractions(mockUserGroupDAO);
	}

	@Test
	public void testValidateGridOwnerWithNonExistentOwner() {
		when(mockUserGroupDAO.doesIdExist(0L)).thenReturn(false);

		// call under test
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> manager.validateGridOwner(user, "0"));

		assertEquals("ownerPrincipalId '0' does not exist.", e.getMessage());
		verify(mockUserGroupDAO).doesIdExist(0L);
		verifyNoMoreInteractions(mockUserGroupDAO);
	}

	@Test
	public void testValidateGridOwnerWithNonExistentOwnerAsAdmin() {
		UserInfo admin = new UserInfo(true, 333L);
		when(mockUserGroupDAO.doesIdExist(0L)).thenReturn(false);

		// call under test
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> manager.validateGridOwner(admin, "0"));

		assertEquals("ownerPrincipalId '0' does not exist.", e.getMessage());
		verify(mockUserGroupDAO).doesIdExist(0L);
		verifyNoMoreInteractions(mockUserGroupDAO);
	}

	@Test
	public void testValidateGridOwnerWithValidOwnerAsMember() {
		Long teamId = 222L;
		when(mockUserGroupDAO.doesIdExist(teamId)).thenReturn(true);

		// call under test
		Long result = manager.validateGridOwner(user, teamId.toString());

		assertEquals(teamId, result);
	}

	@Test
	public void testValidateGridOwnerWithValidOwnerAsAdmin() {
		UserInfo admin = new UserInfo(true, 333L);
		Long teamId = 999L;
		when(mockUserGroupDAO.doesIdExist(teamId)).thenReturn(true);

		// call under test
		Long result = manager.validateGridOwner(admin, teamId.toString());

		assertEquals(teamId, result);
	}

	@Test
	public void testValidateGridOwnerWithUnauthorizedUser() {
		Long teamId = 999L;
		when(mockUserGroupDAO.doesIdExist(teamId)).thenReturn(true);

		// call under test
		assertThrows(UnauthorizedException.class, () -> manager.validateGridOwner(user, teamId.toString()));
	}

	@Test
	public void testValidateGridOwnerWithInvalidFormat() {
		// call under test
		assertThrows(IllegalArgumentException.class, () -> manager.validateGridOwner(user, "not-a-number"));

		verifyNoMoreInteractions(mockUserGroupDAO);
	}
}
