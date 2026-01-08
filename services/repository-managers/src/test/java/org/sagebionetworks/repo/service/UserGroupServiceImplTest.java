package org.sagebionetworks.repo.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.manager.RealmManager;
import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.model.auth.Realm;
import org.sagebionetworks.repo.model.auth.RealmIdList;
import org.sagebionetworks.repo.model.auth.RealmPrincipal;

@ExtendWith(MockitoExtension.class)
class UserGroupServiceImplTest {
	
	@Mock
	private UserManager mockUserManager;
	
	@Mock
	private RealmManager mockRealmManager;
	
	@InjectMocks
	private UserGroupServiceImpl userGroupService;
	
	private static final String REALM_ID = "1";
	
	@Test
	public void testListRealms() {
		String realmId1 = "1";
		String realmId2 = "2";
		RealmIdList realmIdList = new RealmIdList();
		realmIdList.setRealms(List.of(realmId1, realmId2));
		when(mockRealmManager.listRealmIds()).thenReturn(realmIdList);
		// method under test
		RealmIdList result = userGroupService.listRealmIds();
		assertEquals(realmIdList, result);
		verify(mockRealmManager).listRealmIds();
	}
	
	@Test
	public void testGetRealm() {
		Realm expected = new Realm();
		expected.setId(REALM_ID);
		when (mockRealmManager.getRealm(REALM_ID)).thenReturn(expected);
		
		// method under test
		Realm actual = userGroupService.getRealm(REALM_ID);
		
		assertEquals(expected, actual);
		verify(mockRealmManager).getRealm(REALM_ID);
	}

	@Test
	public void testGetRealmPrincipals() {
		RealmPrincipal expected = new RealmPrincipal();
		expected.setRealmId(REALM_ID);
		when (mockRealmManager.getRealmPrincipals(REALM_ID)).thenReturn(expected);
		
		// method under test
		RealmPrincipal actual = userGroupService.getRealmPrincipals(REALM_ID);
		
		assertEquals(expected, actual);
		verify(mockRealmManager).getRealmPrincipals(REALM_ID);
	}

}
