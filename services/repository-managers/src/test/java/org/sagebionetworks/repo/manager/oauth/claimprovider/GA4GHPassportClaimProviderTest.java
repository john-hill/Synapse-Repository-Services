package org.sagebionetworks.repo.manager.oauth.claimprovider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.sagebionetworks.repo.model.AccessApprovalDAO;
import org.sagebionetworks.repo.model.AccessRequirementDAO;
import org.sagebionetworks.repo.model.GroupMembersDAO;
import org.sagebionetworks.repo.model.oauth.OIDCClaimName;
import org.sagebionetworks.repo.model.oauth.OIDCClaimsRequestDetails;
import org.sagebionetworks.util.Clock;

import com.google.common.collect.ImmutableList;

@RunWith(MockitoJUnitRunner.class)
public class GA4GHPassportClaimProviderTest {
	
	@Mock
	private AccessRequirementDAO accessRequirementDao;
	
	@Mock
	private AccessApprovalDAO accessApprovalDao;
	
	@Mock
	private Clock clock;
	
	@InjectMocks
	private GA4GHPassportClaimProvider claimProvider;
	
	private static final String USER_ID = "101";
	
	private static final String HOST_NAME = "repo.sage.org";
	private static final String AUTH_ENDPOINT = "https://"+HOST_NAME+"/auth/v1";
	
	private OIDCClaimsRequestDetails teamRequest;
	
	private static String createArUrl(String arId) {
		return "https://"+HOST_NAME+"/accessRequirement/"+arId;
	}
	
	@Before
	public void setUp() {
		teamRequest = new OIDCClaimsRequestDetails();
		teamRequest.setValue(createArUrl("111"));
		teamRequest.setValues(ImmutableList.of(createArUrl("222"), createArUrl("333")));
	}
	
	@Test
	public void testGetArIdFromDetail() {
		// TODO test happy case
		// TODO test invalid string
	}
	
	@Test
	public void testGetArIdFromDetailSelfSigned() {
		// TODO
	}

	@Test
	public void testGetArIdFromDetailACTApproved() {
		// TODO
	}

	@Test
	public void testClaim() {
		List<String> teams = Collections.singletonList(TEAM_ID); // the list of teams to which the user belongs
		when(groupMembersDAO.filterUserGroups(USER_ID, ImmutableList.of("102",TEAM_ID))).thenReturn(teams);
		// method under test
		assertEquals(OIDCClaimName.team, claimProvider.getName());
		// method under test
		assertNotNull(claimProvider.getDescription());
		
		// method under test
		assertEquals(Collections.singletonList(TEAM_ID), claimProvider.getClaim(USER_ID, teamRequest, AUTH_ENDPOINT));
	}

	@Test
	public void testClaimEmpty() {
		// what if the user belongs to no teams?
		when(accessApprovalDao.getRequirementsUserHasApprovals(USER_ID, Collections.EMPTY_LIST)).thenReturn(Collections.EMPTY_SET);
		// method under test
		assertEquals(Collections.EMPTY_LIST, claimProvider.getClaim(USER_ID, teamRequest, AUTH_ENDPOINT));
	}

}
