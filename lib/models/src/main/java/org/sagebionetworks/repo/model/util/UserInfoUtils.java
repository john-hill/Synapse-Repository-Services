package org.sagebionetworks.repo.model.util;

import java.util.ArrayList;

import org.sagebionetworks.repo.model.User;
import org.sagebionetworks.repo.model.Principal;
import org.sagebionetworks.repo.model.UserInfo;

/**
 * Creates a stubbed user Info for test.
 * @author jmhill
 *
 */
public class UserInfoUtils {
	
	public static UserInfo createValidUserInfo(boolean isAdmin){
		User user = new User();
		user.setId("23");
		user.setUserId("someTestUser@gmail.com");
		Principal group = new Principal();
		group.setId("3");
		group.setPrincipalName("foo@bar.com");
		group.setIsIndividual(true);
		UserInfo info = new UserInfo(isAdmin);
		info.setUser(user);
		info.setIndividualGroup(group);
		info.setGroups(new ArrayList<Principal>());
		UserInfo.validateUserInfo(info);
		return info;
	}

}
