package org.sagebionetworks.repo.manager.docusign;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.sagebionetworks.docusign.DocuSignClient;
import org.sagebionetworks.docusign.RoleLabelKey;
import org.sagebionetworks.repo.model.AccessRequirement;
import org.sagebionetworks.repo.model.AccessRequirementDAO;
import org.sagebionetworks.repo.model.AuthorizationUtils;
import org.sagebionetworks.repo.model.ManagedACTAccessRequirement;
import org.sagebionetworks.repo.model.NextPageToken;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.UserProfile;
import org.sagebionetworks.repo.model.UserProfileDAO;
import org.sagebionetworks.repo.model.dao.NotificationEmailDAO;
import org.sagebionetworks.repo.model.dataaccess.AccessType;
import org.sagebionetworks.repo.model.dataaccess.AccessorChange;
import org.sagebionetworks.repo.model.dataaccess.PrincipalInvestigator;
import org.sagebionetworks.repo.model.dataaccess.RequestInterface;
import org.sagebionetworks.repo.model.dataaccess.SigningOfficial;
import org.sagebionetworks.repo.model.dbo.dao.dataaccess.RequestDAO;
import org.sagebionetworks.repo.model.educ.EDucTemplateListRequest;
import org.sagebionetworks.repo.model.educ.EDucTemplatePage;
import org.sagebionetworks.repo.model.principal.PrincipalAliasDAO;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.stereotype.Service;

@Service
public class EDucManager {

	private final DocuSignClient docuSignClient;
	private final RequestDAO requestDao;
	private final AccessRequirementDAO accessRequirementDao;
	private final PrincipalAliasDAO principalAliasDao;
	private final NotificationEmailDAO notificationEmailDao;
	private final UserProfileDAO userProfileDao;

	public EDucManager(DocuSignClient docuSignClient, RequestDAO requestDao,
			AccessRequirementDAO accessRequirementDao, PrincipalAliasDAO principalAliasDao,
			NotificationEmailDAO notificationEmailDao, UserProfileDAO userProfileDao) {
		this.docuSignClient = docuSignClient;
		this.requestDao = requestDao;
		this.accessRequirementDao = accessRequirementDao;
		this.principalAliasDao = principalAliasDao;
		this.notificationEmailDao = notificationEmailDao;
		this.userProfileDao = userProfileDao;
	}

	public EDucTemplatePage listTemplates(UserInfo userInfo, EDucTemplateListRequest request) throws Exception {
		ValidateArgument.required(userInfo, "userInfo");
		ValidateArgument.required(request, "request");
		if (!AuthorizationUtils.isACTTeamMemberOrAdmin(userInfo)) {
			throw new UnauthorizedException("Only ACT member can perform this action.");
		}
		NextPageToken token = new NextPageToken(request.getNextPageToken());
		int startPosition = (int) token.getOffset();
		int count = (int) token.getLimitForQuery();
		EDucTemplatePage page = docuSignClient.listTemplates(startPosition, count);
		page.setNextPageToken(token.getNextPageTokenForCurrentResults(page.getResults()));
		return page;
	}

	public RequestInterface routeForSignature(UserInfo userInfo, String requestId) {
		ValidateArgument.required(userInfo, "userInfo");
		ValidateArgument.required(requestId, "requestId");

		RequestInterface request = requestDao.get(requestId);

		if (!AuthorizationUtils.isUserCreatorOrAdmin(userInfo, request.getCreatedBy())) {
			throw new UnauthorizedException("Only the request creator or an administrator can route for signature.");
		}

		if (request.getEDucSignatureEnvelopeId() != null) {
			throw new IllegalArgumentException("This request already has a signature envelope: "
					+ request.getEDucSignatureEnvelopeId());
		}

		AccessRequirement ar = accessRequirementDao.get(request.getAccessRequirementId());
		if (!(ar instanceof ManagedACTAccessRequirement managedAr)) {
			throw new IllegalArgumentException("The access requirement is not a ManagedACTAccessRequirement.");
		}
		if (!Boolean.TRUE.equals(managedAr.getIsDUCRequired())) {
			throw new IllegalArgumentException("The access requirement does not require a DUC.");
		}

		String templateId = managedAr.getEDucTemplateId();
		if (templateId == null || templateId.isBlank()) {
			throw new IllegalArgumentException("The access requirement does not have an eDUC template ID configured.");
		}

		Map<String, String> roleEmails = buildRoleEmails(request);
		Map<RoleLabelKey, String> tabValues = buildTabValues(request);

		String envelopeId = docuSignClient.createAndSendEnvelope(templateId, roleEmails, tabValues);

		request.setEDucSignatureEnvelopeId(envelopeId);
		return requestDao.update(request);
	}

	private Map<String, String> buildRoleEmails(RequestInterface request) {
		Map<String, String> roleEmails = new LinkedHashMap<>();

		PrincipalInvestigator pi = request.getPrincipalInvestigator();
		ValidateArgument.required(pi, "principalInvestigator");
		roleEmails.put("principal_investigator", pi.getInstitutionalEmail());

		SigningOfficial so = request.getSigningOfficial();
		ValidateArgument.required(so, "signingOfficial");
		roleEmails.put("signing_official", so.getInstitutionalEmail());

		List<AccessorChange> accessorChanges = request.getAccessorChanges();
		if (accessorChanges != null) {
			int collaboratorIndex = 1;
			for (AccessorChange change : accessorChanges) {
				if (AccessType.GAIN_ACCESS.equals(change.getType())
						|| AccessType.RENEW_ACCESS.equals(change.getType())) {
					String email = notificationEmailDao.getNotificationEmailForPrincipal(
							Long.parseLong(change.getUserId()));
					roleEmails.put("collaborator_" + collaboratorIndex, email);
					collaboratorIndex++;
				}
			}
		}

		return roleEmails;
	}

	private Map<RoleLabelKey, String> buildTabValues(RequestInterface request) {
		Map<RoleLabelKey, String> tabValues = new LinkedHashMap<>();

		SigningOfficial so = request.getSigningOfficial();
		addIfPresent(tabValues, "signing_official", "signing_official_name", so.getName());
		addIfPresent(tabValues, "signing_official", "signing_official_title", so.getTitle());
		addIfPresent(tabValues, "signing_official", "signing_official_email", so.getInstitutionalEmail());

		PrincipalInvestigator pi = request.getPrincipalInvestigator();
		addIfPresent(tabValues, "principal_investigator", "principal_investigator_name", pi.getName());
		addIfPresent(tabValues, "principal_investigator", "principal_investigator_title", pi.getTitle());
		addIfPresent(tabValues, "principal_investigator", "principal_investigator_email", pi.getInstitutionalEmail());
		addIfPresent(tabValues, "principal_investigator", "principal_investigator_institution", request.getInstitution());

		String piUserName = principalAliasDao.getUserName(Long.parseLong(pi.getUserId()));
		addIfPresent(tabValues, "principal_investigator", "principal_investigator_user_name", piUserName);

		List<AccessorChange> accessorChanges = request.getAccessorChanges();
		if (accessorChanges != null) {
			int collaboratorIndex = 1;
			for (AccessorChange change : accessorChanges) {
				if (AccessType.GAIN_ACCESS.equals(change.getType())
						|| AccessType.RENEW_ACCESS.equals(change.getType())) {
					String role = "collaborator_" + collaboratorIndex;
					String userId = change.getUserId();

					String userName = principalAliasDao.getUserName(Long.parseLong(userId));
					addIfPresent(tabValues, role, role + "_user_name", userName);

					UserProfile profile = userProfileDao.get(userId);
					String fullName = buildFullName(profile.getFirstName(), profile.getLastName());
					addIfPresent(tabValues, role, role + "_name", fullName);

					collaboratorIndex++;
				}
			}
		}

		return tabValues;
	}

	private static void addIfPresent(Map<RoleLabelKey, String> tabValues, String roleName,
			String tabLabel, String value) {
		if (value != null && !value.isEmpty()) {
			tabValues.put(new RoleLabelKey(roleName, tabLabel), value);
		}
	}

	static String buildFullName(String firstName, String lastName) {
		if (firstName == null && lastName == null) {
			return null;
		}
		if (firstName == null) {
			return lastName;
		}
		if (lastName == null) {
			return firstName;
		}
		return firstName + " " + lastName;
	}
}
