package org.sagebionetworks.repo.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.manager.team.TeamManager;
import org.sagebionetworks.repo.model.RealmDao;
import org.sagebionetworks.repo.model.Team;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserGroupDAO;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.auth.IdentityProvider;
import org.sagebionetworks.repo.model.auth.OAuthIdentityProvider;
import org.sagebionetworks.repo.model.auth.Realm;
import org.sagebionetworks.repo.model.auth.RealmIdList;
import org.sagebionetworks.repo.model.auth.RealmPrincipal;
import org.sagebionetworks.repo.model.oauth.OAuthProvider;
import org.sagebionetworks.repo.model.principal.AliasType;
import org.sagebionetworks.repo.model.principal.PrincipalAlias;
import org.sagebionetworks.repo.model.principal.PrincipalAliasDAO;

@ExtendWith(MockitoExtension.class)
class RealmManagerImplTest {
	
	@Mock
	private RealmDao realmDao;
	
	@Mock
	private UserGroupDAO userGroupDAO;
	
	@Mock
	private PrincipalAliasDAO principalAliasDAO;
	
	@Mock
	private TeamManager teamManager;

	@InjectMocks
	RealmManagerImpl realmManager;

	private UserInfo userInfo;
	private UserInfo adminUserInfo;
	
	@BeforeEach
	void setUp() throws Exception {
		userInfo = new UserInfo(false);
		adminUserInfo = new UserInfo(true);
	}
	
	private static final String ID ="101";
	private static final String REALM_NAME ="realm-name";
	private static final List<IdentityProvider> IDP_LIST = List.of(new OAuthIdentityProvider().
			setProvider(OAuthProvider.GOOGLE_OAUTH_2_0));
	private static final String expectedRealmAnonymousPrincipalAlias = REALM_NAME+" Anonymous";
	private static final String expectedRealmAuthenticatedPrincipalAlias = REALM_NAME+" Authenticated Users";
	private static final String expectedRealmPublicPrincipalAlias = REALM_NAME+" Public";
	private static final String expectedRealmAdminPrincipalAlias = REALM_NAME+" Administrators";


	@Test
	void testCreate() {
		Realm mockCreated = new Realm();
		mockCreated.setCreatedOn(new Date());
		mockCreated.setId(ID);
		mockCreated.setIdentityProvider(IDP_LIST);
		mockCreated.setName(REALM_NAME);
		when(realmDao.createRealm(any())).thenReturn(mockCreated);
		
		when(principalAliasDAO.isAliasAvailable(any())).thenReturn(true);
//		when(principalAliasDAO.isAliasAvailable(eq(expectedRealmAnonymousPrincipalAlias))).thenReturn(true);
//		when(principalAliasDAO.isAliasAvailable(eq(expectedRealmAuthenticatedPrincipalAlias))).thenReturn(true);
//		when(principalAliasDAO.isAliasAvailable(eq(expectedRealmPublicPrincipalAlias))).thenReturn(true);
		
		Long anonymousId = 101L;
		Long authenticatedId = 102L;
		Long publicId = 103L;
		when(userGroupDAO.create(any())).thenReturn(anonymousId, authenticatedId, publicId);
		
		when(teamManager.create(any(), any())).thenAnswer(invocation -> {
            // Retrieve the argument at index 1 (which is a Team)
            Team team = invocation.getArgument(1); 
            team.setId("999");
            return team;
        });
		
		Realm realm = new Realm();
		realm.setName(REALM_NAME);
		realm.setIdentityProvider(IDP_LIST);
		// method under test
		Realm created = realmManager.createRealm(adminUserInfo, realm);
		assertEquals(mockCreated, created);
		verify(realmDao).createRealm(realm);
		
		ArgumentCaptor<PrincipalAlias> principalAliasCaptor = ArgumentCaptor.forClass(PrincipalAlias.class);
		verify(principalAliasDAO, times(3)).bindAliasToPrincipal(principalAliasCaptor.capture());
		List<PrincipalAlias> boundValues = principalAliasCaptor.getAllValues();
		assertEquals(3, boundValues.size());
		PrincipalAlias anonymousAlias = boundValues.get(0);
		assertEquals(expectedRealmAnonymousPrincipalAlias, anonymousAlias.getAlias());
		assertEquals(AliasType.USER_NAME, anonymousAlias.getType());
		PrincipalAlias authenticatedAlias = boundValues.get(1);
		assertEquals(expectedRealmAuthenticatedPrincipalAlias, authenticatedAlias.getAlias());
		assertEquals(AliasType.TEAM_NAME, authenticatedAlias.getType());
		PrincipalAlias publicAlias = boundValues.get(2);
		assertEquals(expectedRealmPublicPrincipalAlias, publicAlias.getAlias());
		assertEquals(AliasType.TEAM_NAME, publicAlias.getType());
		
		ArgumentCaptor<Team> teamCaptor = ArgumentCaptor.forClass(Team.class);
		verify(teamManager).create(eq(adminUserInfo), teamCaptor.capture());
		Team adminTeam = teamCaptor.getValue();
		assertEquals(expectedRealmAdminPrincipalAlias, adminTeam.getName());
		assertFalse(adminTeam.getCanPublicJoin());

		
		ArgumentCaptor<RealmPrincipal> realmPrincipalCaptor = ArgumentCaptor.forClass(RealmPrincipal.class);
		verify(realmDao).createRealmPrincipals(realmPrincipalCaptor.capture());
		RealmPrincipal actualRealmPrincipal = realmPrincipalCaptor.getValue();
		assertEquals(ID, actualRealmPrincipal.getRealmId());
		assertEquals(String.valueOf(anonymousId), actualRealmPrincipal.getAnonymousUser());
		assertEquals(String.valueOf(authenticatedId), actualRealmPrincipal.getAuthenticatedUsers());
		assertEquals(String.valueOf(publicId), actualRealmPrincipal.getPublicGroup());
		assertEquals(adminTeam.getId(), actualRealmPrincipal.getAdministrativeGroup());
	}
	
	@Test
	void testCreateRealmNotAdmin() {
		Realm realm = new Realm();
		// method under test
		assertThrows(UnauthorizedException.class, ()->{realmManager.createRealm(userInfo, realm);});
	}
	
	@Test
	void testCreateRealmMissingRequired() {
		// fail if missing name or idps
	}
	
	@Test
	void testCreateRealmAliasUnavailable() {
		
	}
	
	@Test
	void testListRealms() {
		// method under test
		RealmIdList idList = realmManager.listRealmIds();
		assertEquals(1, idList.getRealms().size());
		assertEquals(ID, idList.getRealms().get(0));
	}
	
	@Test
	void testGetRealm() {
		// method under test
		Realm retrieved = realmManager.getRealm(ID);
		
	}

	@Test
	void testGetRealmPrincipals() {
		// method under test
		RealmPrincipal realmPrincipal = realmManager.getRealmPrincipals(ID);
		

	}
	
	@Test
	void testDeleteRealm() {
		// method under test
		realmManager.deleteRealm(userInfo, ID);
		

	}
	
	@Test
	void testDeleteRealmNotAdmin() {
		
	}
}
