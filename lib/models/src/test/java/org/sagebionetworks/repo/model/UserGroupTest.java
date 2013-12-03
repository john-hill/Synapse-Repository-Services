package org.sagebionetworks.repo.model;

import static org.junit.Assert.*;

import org.junit.Test;
import org.sagebionetworks.repo.model.util.UserGroupUtil;

public class UserGroupTest {

	@Test
	public  void testIsEmailAddress(){
		assertTrue(UserGroupUtil.isEmailAddress("something@gmail.com"));
		assertFalse(UserGroupUtil.isEmailAddress("PUBLIC"));
	}

	@Test (expected=IllegalArgumentException.class)
	public void validateNull(){
		UserGroupUtil.validate(null);
	}

	@Test (expected=UserNotFoundException.class)
	public void validateNullId(){
		Principal userGroup = new Principal();
		UserGroupUtil.validate(userGroup);
	}

	@Test (expected=UserNotFoundException.class)
	public void validateNullName(){
		Principal userGroup = new Principal();
		userGroup.setId("99");
		UserGroupUtil.validate(userGroup);
	}

	@Test
	public void validateValid(){
		Principal userGroup = new Principal();
		userGroup.setId("99");
		userGroup.setPrincipalName("something@somewhere.com");
		userGroup.setIsIndividual(true);
		UserGroupUtil.validate(userGroup);
	}

	@Test(expected=UserNotFoundException.class)
	public void validateGroupWithEmailAddressName(){
		Principal userGroup = new Principal();
		userGroup.setId("99");
		userGroup.setPrincipalName("something@somewhere.com");
		userGroup.setIsIndividual(false);
		UserGroupUtil.validate(userGroup);
	}

	@Test(expected=UserNotFoundException.class)
	public void validateUserWithoutEmailAddressName(){
		Principal userGroup = new Principal();
		userGroup.setId("99");
		userGroup.setPrincipalName("someName");
		userGroup.setIsIndividual(true);
		UserGroupUtil.validate(userGroup);
	}
}
