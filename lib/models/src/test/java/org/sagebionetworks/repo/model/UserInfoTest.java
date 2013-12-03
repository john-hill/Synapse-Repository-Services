package org.sagebionetworks.repo.model;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class UserInfoTest {

	@Test (expected=IllegalArgumentException.class)
	public void testValidateNull(){
		UserInfo.validateUserInfo(null);
	}

	@Test (expected=IllegalArgumentException.class)
	public void testValidateNullUser(){
		UserInfo info = new UserInfo(false);
		UserInfo.validateUserInfo(info);
	}

	@Test (expected=UserNotFoundException.class)
	public void testValidateNullUserId(){
		UserInfo info = new UserInfo(false);
		User user = new User();
		info.setUser(user);
		UserInfo.validateUserInfo(info);
	}

	@Test (expected=UserNotFoundException.class)
	public void testValidateNullUserUserId(){
		UserInfo info = new UserInfo(false);
		User user = new User();
		user.setId("101");
		user.setUserId("myId@idstore.org");
		info.setUser(user);
		Principal ind = new Principal();
		ind.setId("9");
		ind.setPrincipalName("one");
		info.setIndividualGroup(ind);
		List<Principal> groups = new ArrayList<Principal>();
		// This will have null values
		groups.add(new Principal());
		info.setGroups(groups);
		UserInfo.validateUserInfo(info);
	}

	@Test
	public void testValidateValid(){
		UserInfo info = new UserInfo(false);
		User user = new User();
		user.setId("101");
		user.setUserId("myId@idstore.org");
		info.setUser(user);
		Principal ind = new Principal();
		ind.setId("9");
		ind.setPrincipalName("one");
		ind.setIsIndividual(false);
		info.setIndividualGroup(ind);
		List<Principal> groups = new ArrayList<Principal>();
		// This will have null values
		Principal group = new Principal();
		group.setId("0");
		group.setPrincipalName("groupies");
		group.setIsIndividual(false);
		groups.add(group);
		info.setGroups(groups);
		UserInfo.validateUserInfo(info);
	}
}
