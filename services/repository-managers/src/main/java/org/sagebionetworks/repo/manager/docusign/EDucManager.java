package org.sagebionetworks.repo.manager.docusign;

import org.sagebionetworks.docusign.DocuSignClient;
import org.sagebionetworks.repo.model.AuthorizationUtils;
import org.sagebionetworks.repo.model.NextPageToken;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.educ.EDucTemplatePage;
import org.sagebionetworks.repo.web.NotFoundException;
import org.sagebionetworks.repo.web.ServiceUnavailableException;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.stereotype.Service;

@Service
public class EDucManager {

	private final DocuSignClient docuSignClient;

	public EDucManager(DocuSignClient docuSignClient) {
		this.docuSignClient = docuSignClient;
	}

	public EDucTemplatePage listTemplates(UserInfo userInfo, String nextPageToken) throws Exception {
		ValidateArgument.required(userInfo, "userInfo");
		if (!AuthorizationUtils.isACTTeamMemberOrAdmin(userInfo)) {
			throw new UnauthorizedException("Only ACT member can perform this action.");
		}
		NextPageToken token = new NextPageToken(nextPageToken);
		int startPosition = (int) token.getOffset();
		int count = (int) token.getLimitForQuery();
		EDucTemplatePage page = docuSignClient.listTemplates(startPosition, count);
		page.setNextPageToken(token.getNextPageTokenForCurrentResults(page.getResults()));
		return page;
	}
}
