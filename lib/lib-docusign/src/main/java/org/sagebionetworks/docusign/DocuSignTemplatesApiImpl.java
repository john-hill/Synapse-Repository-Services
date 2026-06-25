package org.sagebionetworks.docusign;

import org.springframework.stereotype.Service;

import com.docusign.esign.api.TemplatesApi;
import com.docusign.esign.client.ApiClient;
import com.docusign.esign.client.ApiException;
import com.docusign.esign.model.EnvelopeTemplateResults;

@Service
class DocuSignTemplatesApiImpl implements DocuSignTemplatesApi {

	@Override
	public EnvelopeTemplateResults listTemplates(String basePath, String accessToken,
			String accountId, String startPosition, String count) throws ApiException {
		ApiClient apiClient = new ApiClient(basePath);
		apiClient.addDefaultHeader("Authorization", "Bearer " + accessToken);
		TemplatesApi templatesApi = new TemplatesApi(apiClient);
		TemplatesApi.ListTemplatesOptions options = templatesApi.new ListTemplatesOptions();
		options.setStartPosition(startPosition);
		options.setCount(count);
		return templatesApi.listTemplates(accountId, options);
	}
}
