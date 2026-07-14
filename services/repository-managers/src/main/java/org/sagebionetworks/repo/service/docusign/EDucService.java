package org.sagebionetworks.repo.service.docusign;

import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.manager.docusign.EDucManager;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.duc.DucFileHandleId;
import org.sagebionetworks.repo.model.educ.EDucTemplateListRequest;
import org.sagebionetworks.repo.model.educ.EDucTemplatePage;
import org.sagebionetworks.repo.model.educ.SignatureQuota;
import org.springframework.stereotype.Service;

@Service
public class EDucService {

	private final UserManager userManager;
	private final EDucManager eDucManager;

	public EDucService(UserManager userManager, EDucManager eDucManager) {
		this.userManager = userManager;
		this.eDucManager = eDucManager;
	}

	public EDucTemplatePage listTemplates(Long userId, EDucTemplateListRequest request) throws Exception {
		UserInfo userInfo = userManager.getUserInfo(userId);
		return eDucManager.listTemplates(userInfo, request);
	}

	public SignatureQuota routeForSignature(Long userId, String requestId) {
		UserInfo userInfo = userManager.getUserInfo(userId);
		return eDucManager.routeForSignature(userInfo, requestId);
	}

	public void cancelSignature(Long userId, String requestId) {
		UserInfo userInfo = userManager.getUserInfo(userId);
		eDucManager.cancelSignature(userInfo, requestId);
	}

	public DucFileHandleId getSignedDocumentFileHandle(Long userId, String requestId) {
		UserInfo userInfo = userManager.getUserInfo(userId);
		return eDucManager.getSignedDocumentFileHandle(userInfo, requestId);
	}
}
