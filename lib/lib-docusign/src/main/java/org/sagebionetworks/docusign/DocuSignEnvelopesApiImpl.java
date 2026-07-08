package org.sagebionetworks.docusign;

import org.springframework.stereotype.Service;

import com.docusign.esign.api.EnvelopesApi;
import com.docusign.esign.client.ApiClient;
import com.docusign.esign.model.EnvelopeDefinition;
import com.docusign.esign.model.EnvelopeSummary;

@Service
class DocuSignEnvelopesApiImpl implements DocuSignEnvelopesApi {

	private final DocuSignClientConfig config;
	private final DocuSignApiRetryHelper retryHelper;

	DocuSignEnvelopesApiImpl(DocuSignClientConfig config, DocuSignAccessTokenProvider accessTokenProvider) {
		this.config = config;
		this.retryHelper = new DocuSignApiRetryHelper(accessTokenProvider);
	}

	@Override
	public EnvelopeSummary createEnvelope(EnvelopeDefinition envelopeDefinition) {
		return retryHelper.executeWithRetry(accessToken -> {
			ApiClient apiClient = new ApiClient(config.getBasePath());
			apiClient.addDefaultHeader("Authorization", "Bearer " + accessToken);
			EnvelopesApi envelopesApi = new EnvelopesApi(apiClient);
			return envelopesApi.createEnvelope(config.getAccountId(), envelopeDefinition);
		});
	}
}
