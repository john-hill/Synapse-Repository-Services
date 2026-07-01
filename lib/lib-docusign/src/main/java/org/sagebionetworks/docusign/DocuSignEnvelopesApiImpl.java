package org.sagebionetworks.docusign;

import org.springframework.stereotype.Service;

import com.docusign.esign.api.EnvelopesApi;
import com.docusign.esign.client.ApiClient;
import com.docusign.esign.client.ApiException;
import com.docusign.esign.model.EnvelopeDefinition;
import com.docusign.esign.model.EnvelopeSummary;

@Service
class DocuSignEnvelopesApiImpl implements DocuSignEnvelopesApi {

	@Override
	public EnvelopeSummary createEnvelope(String basePath, String accessToken,
			String accountId, EnvelopeDefinition envelopeDefinition) throws ApiException {
		ApiClient apiClient = new ApiClient(basePath);
		apiClient.addDefaultHeader("Authorization", "Bearer " + accessToken);
		EnvelopesApi envelopesApi = new EnvelopesApi(apiClient);
		return envelopesApi.createEnvelope(accountId, envelopeDefinition);
	}
}
