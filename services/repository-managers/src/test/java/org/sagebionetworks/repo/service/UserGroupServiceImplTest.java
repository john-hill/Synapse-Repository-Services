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
import org.sagebionetworks.repo.model.auth.RealmIdList;

@ExtendWith(MockitoExtension.class)
class UserGroupServiceImplTest {
	
	@Mock
	private UserManager mockUserManager;
	
	@Mock
	private RealmManager mockRealmManager;
	
	@InjectMocks
	private UserGroupServiceImpl userGroupService;
	
	// TODO add tests for getUserGroups, getRealm, getRealmPrincipals

	@Test
	public void testListRealms() {
		String realmId1 = "1";
		String realmId2 = "2";
		RealmIdList realmIdList = new RealmIdList();
		realmIdList.setRealms(List.of(realmId1, realmId2));
		when(mockRealmManager.listRealmIds()).thenReturn(realmIdList);
		RealmIdList result = userGroupService.listRealmIds();
		assertEquals(realmIdList, result);
		verify(mockRealmManager).listRealmIds();
	}

}
