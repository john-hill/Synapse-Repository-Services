package org.sagebionetworks.docusign;

import java.util.List;

import org.sagebionetworks.repo.model.duc.DucSignatureStatus;

/*
 * Allows returning the signers' emails, which are not part of the DucSignatureStatus DTO
 */
public record EnvelopeStatusResult(DucSignatureStatus status, List<String> signerEmails) {
}
