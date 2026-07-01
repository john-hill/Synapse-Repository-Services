package org.sagebionetworks.docusign;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.docusign.esign.model.DateSigned;
import com.docusign.esign.model.Email;
import com.docusign.esign.model.EmailAddress;
import com.docusign.esign.model.EnvelopeTemplate;
import com.docusign.esign.model.FullName;
import com.docusign.esign.model.Recipients;
import com.docusign.esign.model.SignHere;
import com.docusign.esign.model.Signer;
import com.docusign.esign.model.Tabs;
import com.docusign.esign.model.Text;
import com.docusign.esign.model.Title;

class DocuSignTemplateValidator {

	private static final String SIGNING_OFFICIAL = "signing_official";
	private static final String PRINCIPAL_INVESTIGATOR = "principal_investigator";
	private static final Pattern COLLABORATOR_PATTERN = Pattern.compile("collaborator_(\\d+)");
	private static final int MAX_COLLABORATORS = 98;

	enum TabType {
		TEXT,
		FULL_NAME,
		TITLE,
		EMAIL,
		SIGN_HERE,
		DATE_SIGNED
	}

	record RequiredTab(String label, TabType type) {}

	static final List<RequiredTab> SIGNING_OFFICIAL_TABS = List.of(
			new RequiredTab("signing_official_name", TabType.FULL_NAME),
			new RequiredTab("signing_official_title", TabType.TITLE),
			new RequiredTab("signing_official_email", TabType.EMAIL),
			new RequiredTab("signing_official_signature", TabType.SIGN_HERE),
			new RequiredTab("signing_official_date", TabType.DATE_SIGNED)
	);

	static final List<RequiredTab> PRINCIPAL_INVESTIGATOR_TABS = List.of(
			new RequiredTab("principal_investigator_institution", TabType.TEXT),
			new RequiredTab("principal_investigator_name", TabType.FULL_NAME),
			new RequiredTab("principal_investigator_title", TabType.TITLE),
			new RequiredTab("principal_investigator_email", TabType.EMAIL),
			new RequiredTab("principal_investigator_user_name", TabType.TEXT),
			new RequiredTab("principal_investigator_signature", TabType.SIGN_HERE),
			new RequiredTab("principal_investigator_date", TabType.DATE_SIGNED)
	);

	static void validate(EnvelopeTemplate template) {
		Recipients recipients = template.getRecipients();
		if (recipients == null || recipients.getSigners() == null || recipients.getSigners().isEmpty()) {
			throw new IllegalArgumentException("Template has no signer roles defined.");
		}

		Map<String, Signer> signersByRole = recipients.getSigners().stream()
				.collect(Collectors.toMap(Signer::getRoleName, s -> s));

		validateRole(signersByRole, SIGNING_OFFICIAL, SIGNING_OFFICIAL_TABS);
		validateRole(signersByRole, PRINCIPAL_INVESTIGATOR, PRINCIPAL_INVESTIGATOR_TABS);
		validateCollaborators(signersByRole);
	}

	private static void validateRole(Map<String, Signer> signersByRole, String roleName,
			List<RequiredTab> requiredTabs) {
		Signer signer = signersByRole.get(roleName);
		if (signer == null) {
			throw new IllegalArgumentException("Template is missing required role: " + roleName);
		}
		validateTabs(roleName, signer.getTabs(), requiredTabs);
	}

	private static void validateTabs(String roleName, Tabs tabs, List<RequiredTab> requiredTabs) {
		List<String> missing = new ArrayList<>();
		for (RequiredTab required : requiredTabs) {
			if (!hasTab(tabs, required)) {
				missing.add(required.label() + " (" + required.type().name() + ")");
			}
		}
		if (!missing.isEmpty()) {
			Collections.sort(missing);
			throw new IllegalArgumentException(
					"Role '" + roleName + "' is missing required tabs: " + missing);
		}
	}

	private static boolean hasTab(Tabs tabs, RequiredTab required) {
		if (tabs == null) {
			return false;
		}
		return switch (required.type()) {
			case TEXT -> containsLabel(getTextTabLabels(tabs), required.label());
			case FULL_NAME -> containsLabel(getFullNameTabLabels(tabs), required.label());
			case TITLE -> containsLabel(getTitleTabLabels(tabs), required.label());
			case EMAIL -> containsLabel(getEmailTabLabels(tabs), required.label())
					|| containsLabel(getEmailAddressTabLabels(tabs), required.label());
			case SIGN_HERE -> containsLabel(getSignHereTabLabels(tabs), required.label());
			case DATE_SIGNED -> containsLabel(getDateSignedTabLabels(tabs), required.label());
		};
	}

	private static boolean containsLabel(Set<String> labels, String label) {
		return labels.contains(label);
	}

	private static void validateCollaborators(Map<String, Signer> signersByRole) {
		TreeMap<Integer, Signer> collaborators = new TreeMap<>();
		for (Map.Entry<String, Signer> entry : signersByRole.entrySet()) {
			Matcher matcher = COLLABORATOR_PATTERN.matcher(entry.getKey());
			if (matcher.matches()) {
				int index = Integer.parseInt(matcher.group(1));
				collaborators.put(index, entry.getValue());
			}
		}

		if (collaborators.isEmpty()) {
			return;
		}

		int maxIndex = collaborators.lastKey();
		if (maxIndex > MAX_COLLABORATORS) {
			throw new IllegalArgumentException(
					"Collaborator index " + maxIndex + " exceeds maximum of " + MAX_COLLABORATORS + ".");
		}

		for (int i = 1; i <= maxIndex; i++) {
			if (!collaborators.containsKey(i)) {
				throw new IllegalArgumentException(
						"Collaborator roles are not sequential: missing collaborator_" + i + ".");
			}
			Signer signer = collaborators.get(i);
			validateTabs("collaborator_" + i, signer.getTabs(), requiredCollaboratorTabs(i));
		}
	}

	static List<RequiredTab> requiredCollaboratorTabs(int index) {
		String prefix = "collaborator_" + index + "_";
		return List.of(
				new RequiredTab(prefix + "user_name", TabType.TEXT),
				new RequiredTab(prefix + "name", TabType.FULL_NAME),
				new RequiredTab(prefix + "signature", TabType.SIGN_HERE),
				new RequiredTab(prefix + "date", TabType.DATE_SIGNED)
		);
	}

	static Set<String> getTextTabLabels(Tabs tabs) {
		if (tabs.getTextTabs() == null) {
			return Collections.emptySet();
		}
		Set<String> labels = new HashSet<>();
		for (Text t : tabs.getTextTabs()) {
			if (t.getTabLabel() != null) {
				labels.add(t.getTabLabel());
			}
		}
		return labels;
	}

	static Set<String> getFullNameTabLabels(Tabs tabs) {
		if (tabs.getFullNameTabs() == null) {
			return Collections.emptySet();
		}
		Set<String> labels = new HashSet<>();
		for (FullName f : tabs.getFullNameTabs()) {
			if (f.getTabLabel() != null) {
				labels.add(f.getTabLabel());
			}
		}
		return labels;
	}

	static Set<String> getTitleTabLabels(Tabs tabs) {
		if (tabs.getTitleTabs() == null) {
			return Collections.emptySet();
		}
		Set<String> labels = new HashSet<>();
		for (Title t : tabs.getTitleTabs()) {
			if (t.getTabLabel() != null) {
				labels.add(t.getTabLabel());
			}
		}
		return labels;
	}

	static Set<String> getEmailTabLabels(Tabs tabs) {
		if (tabs.getEmailTabs() == null) {
			return Collections.emptySet();
		}
		Set<String> labels = new HashSet<>();
		for (Email e : tabs.getEmailTabs()) {
			if (e.getTabLabel() != null) {
				labels.add(e.getTabLabel());
			}
		}
		return labels;
	}

	static Set<String> getEmailAddressTabLabels(Tabs tabs) {
		if (tabs.getEmailAddressTabs() == null) {
			return Collections.emptySet();
		}
		Set<String> labels = new HashSet<>();
		for (EmailAddress e : tabs.getEmailAddressTabs()) {
			if (e.getTabLabel() != null) {
				labels.add(e.getTabLabel());
			}
		}
		return labels;
	}

	static Set<String> getSignHereTabLabels(Tabs tabs) {
		if (tabs.getSignHereTabs() == null) {
			return Collections.emptySet();
		}
		Set<String> labels = new HashSet<>();
		for (SignHere s : tabs.getSignHereTabs()) {
			if (s.getTabLabel() != null) {
				labels.add(s.getTabLabel());
			}
		}
		return labels;
	}

	static Set<String> getDateSignedTabLabels(Tabs tabs) {
		if (tabs.getDateSignedTabs() == null) {
			return Collections.emptySet();
		}
		Set<String> labels = new HashSet<>();
		for (DateSigned d : tabs.getDateSignedTabs()) {
			if (d.getTabLabel() != null) {
				labels.add(d.getTabLabel());
			}
		}
		return labels;
	}
}
