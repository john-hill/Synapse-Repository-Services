package org.sagebionetworks.docusign;

import com.docusign.esign.client.ApiException;
import com.docusign.esign.model.EnvelopeTemplateResults;

/**
 * Test seam: encapsulates the DocuSign Templates API call so that tests can
 * substitute a mock without needing to mock the final SDK classes.
 */
interface TemplatesApiFactory {

	EnvelopeTemplateResults listTemplates(String basePath, String accessToken, String accountId,
			String startPosition, String count) throws ApiException;
}
