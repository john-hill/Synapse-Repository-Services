package org.sagebionetworks.docusign;

public record RoleTabKey(String roleName, String tabLabel) {

	public RoleTabKey {
		if (roleName == null) {
			throw new IllegalArgumentException("roleName is required.");
		}
		if (tabLabel == null) {
			throw new IllegalArgumentException("tabLabel is required.");
		}
	}
}
