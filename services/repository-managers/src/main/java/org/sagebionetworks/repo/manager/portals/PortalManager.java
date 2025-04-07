package org.sagebionetworks.repo.manager.portals;

import java.util.Set;

import org.sagebionetworks.repo.model.ACCESS_TYPE;
import org.sagebionetworks.repo.model.AccessControlList;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.portals.CreateOrUpdatePortalRequest;
import org.sagebionetworks.repo.model.portals.ListPortalsRequest;
import org.sagebionetworks.repo.model.portals.ListPortalsResponse;
import org.sagebionetworks.repo.model.portals.Portal;

public interface PortalManager {
	
	Set<ACCESS_TYPE> DEFAULT_PERMISSIONS = Set.of(ACCESS_TYPE.CREATE, ACCESS_TYPE.READ, ACCESS_TYPE.UPDATE, ACCESS_TYPE.DELETE, ACCESS_TYPE.CHANGE_PERMISSIONS, ACCESS_TYPE.MINT_DOI);
	
	Portal createPortal(UserInfo user, CreateOrUpdatePortalRequest request);

	Portal getPortal(UserInfo user, String portalId);
	
	Portal updatePortal(UserInfo user, String portalId, CreateOrUpdatePortalRequest request);
	
	void deletePortal(UserInfo user, String portalId);	

	ListPortalsResponse listPortals(UserInfo user, ListPortalsRequest request);	

	AccessControlList getPortalAcl(UserInfo user, String portalId);

	AccessControlList updatePortalAcl(UserInfo user, String portalId, AccessControlList acl);

}
