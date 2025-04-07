package org.sagebionetworks.repo.service.portals;

import org.sagebionetworks.repo.model.AccessControlList;
import org.sagebionetworks.repo.model.portals.CreateOrUpdatePortalRequest;
import org.sagebionetworks.repo.model.portals.ListPortalsRequest;
import org.sagebionetworks.repo.model.portals.ListPortalsResponse;
import org.sagebionetworks.repo.model.portals.Portal;

public interface PortalService {

	Portal createPortal(Long userId, CreateOrUpdatePortalRequest request);

	Portal getPortal(Long userId, String portalId);

	ListPortalsResponse listPortals(Long userId, ListPortalsRequest request);

	Portal updatePortal(Long userId, String portalId, CreateOrUpdatePortalRequest request);

	void deletePortal(Long userId, String portalId);
	
	AccessControlList getPortalAcl(Long userId, String portalId);

	AccessControlList updatePortalAcl(Long userId, String portalId, AccessControlList acl);

}
