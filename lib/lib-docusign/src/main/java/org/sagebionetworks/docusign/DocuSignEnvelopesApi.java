package org.sagebionetworks.docusign;

import com.docusign.esign.client.ApiException;
import com.docusign.esign.model.EnvelopeDefinition;
import com.docusign.esign.model.EnvelopeSummary;

interface DocuSignEnvelopesApi {

	EnvelopeSummary createEnvelope(String basePath, String accessToken, String accountId,
			EnvelopeDefinition envelopeDefinition) throws ApiException;
}
