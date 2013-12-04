package org.sagebionetworks.repo.model.util;

import org.sagebionetworks.repo.model.Principal;
import org.sagebionetworks.repo.model.UserNotFoundException;
import org.sagebionetworks.repo.model.Principal;

public class UserGroupUtil {
	/**
	 * Is the passed name an email address
	 * @param name
	 * @return
	 */
	public static boolean isEmailAddress(String name){
		if(name == null)throw new IllegalArgumentException("Name cannot be null");
		int index = name.indexOf("@");
		return index > 0;
	}
	
	/**
	 * Is the passed UserGroup valid?
	 * @param userGroup
	 */
	public static void validate(Principal userGroup) throws UserNotFoundException {

		if (userGroup == null) throw new IllegalArgumentException("Principal cannot be null");

		if (userGroup.getId() == null) throw new UserNotFoundException("Principal.id cannot be null");
		if (userGroup.getPrincipalName() == null) throw new UserNotFoundException("Principal.principalName cannot be null");
		if (userGroup.getIsIndividual() == null) throw new UserNotFoundException("Principal.isIndividual cannot be null");
		// Only an individual can have an email address for a name
		if (isEmailAddress(userGroup.getPrincipalName())) {
			if (!userGroup.getIsIndividual()) throw new UserNotFoundException(
					"Invalid group name: "+userGroup.getPrincipalName()+", group names cannot be email addresses");
		} else {
			if (userGroup.getIsIndividual()) throw new UserNotFoundException(
					"Invalid user name: "+userGroup.getPrincipalName()+", user names must be email addresses");
		}
	}
}
