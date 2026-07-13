package org.sagebionetworks.docusign;

import java.util.List;

import org.sagebionetworks.repo.model.duc.DucSignatureStatus;

public record EnvelopeStatusResult(DucSignatureStatus status, List<String> signerEmails) {
}
