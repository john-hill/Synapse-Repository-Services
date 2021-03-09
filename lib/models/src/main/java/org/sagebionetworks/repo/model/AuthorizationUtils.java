package org.sagebionetworks.repo.model;

import org.sagebionetworks.repo.model.AuthorizationConstants.BOOTSTRAP_PRINCIPAL;
import org.sagebionetworks.util.ValidateArgument;

public class AuthorizationUtils {

	public static boolean isUserAnonymous(UserInfo userInfo) {
		ValidateArgument.required(userInfo, "userInfo");
		return isUserAnonymous(userInfo.getId());
	}

	public static boolean isUserAnonymous(UserGroup ug) {
		return isUserAnonymous(Long.parseLong(ug.getId()));
	}

	public static boolean isUserAnonymous(Long id) {
		return id == null || BOOTSTRAP_PRINCIPAL.ANONYMOUS_USER.getPrincipalId().equals(id);
	}

	/**
	 * returns true iff the user is a certified user
	 * 
	 * @param userInfo
	 * @return
	 */
	public static boolean isCertifiedUser(UserInfo userInfo) {
		if (userInfo.isAdmin()) {
			return true;
		}
		return userInfo.getGroups() != null && userInfo.getGroups()
				.contains(AuthorizationConstants.BOOTSTRAP_PRINCIPAL.CERTIFIED_USERS.getPrincipalId());
	}

	/**
	 * Throws UnauthorizedException if the passed user is anonymous.
	 * 
	 * @param user
	 * @throws UnauthorizedException if the user is anonymous.
	 */
	public static void disallowAnonymous(UserInfo user) throws UnauthorizedException {
		if (AuthorizationUtils.isUserAnonymous(user)) {
			throw new UnauthorizedException("Must login to perform this action");
		}
	}

	public static boolean isACTTeamMemberOrAdmin(UserInfo userInfo) throws DatastoreException, UnauthorizedException {
		if (userInfo.isAdmin()) {
			return true;
		}
		if (userInfo.getGroups() != null) {
			if (userInfo.getGroups()
					.contains(AuthorizationConstants.BOOTSTRAP_PRINCIPAL.ACCESS_AND_COMPLIANCE_GROUP.getPrincipalId()))
				return true;
		}
		return false;
	}

	public static boolean isReportTeamMemberOrAdmin(UserInfo userInfo)
			throws DatastoreException, UnauthorizedException {
		if (userInfo.isAdmin()) {
			return true;
		}
		if (userInfo.getGroups() != null) {
			if (userInfo.getGroups()
					.contains(AuthorizationConstants.BOOTSTRAP_PRINCIPAL.SYNAPSE_REPORT_GROUP.getPrincipalId()))
				return true;
		}
		return false;
	}

	public static boolean isUserCreatorOrAdmin(UserInfo userInfo, String creator) {
		ValidateArgument.required(userInfo, "userInfo");
		if (userInfo.isAdmin()) {
			return true;
		}
		return userInfo.getId().toString().equals(creator);
	}
}
