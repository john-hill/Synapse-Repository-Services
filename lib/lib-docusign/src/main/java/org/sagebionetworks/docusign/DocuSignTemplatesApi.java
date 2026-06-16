package org.sagebionetworks.docusign;

import com.docusign.esign.client.ApiException;
import com.docusign.esign.model.EnvelopeTemplateResults;

/**
 * Wraps the DocuSign Templates REST API so that tests can substitute a mock
 * without needing to mock the final SDK classes.
 */
interface DocuSignTemplatesApi {

	EnvelopeTemplateResults listTemplates(String basePath, String accessToken, String accountId,
			String startPosition, String count) throws ApiException;
}
