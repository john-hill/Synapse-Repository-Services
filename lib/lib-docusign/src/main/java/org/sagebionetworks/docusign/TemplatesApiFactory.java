package org.sagebionetworks.docusign;

import com.docusign.esign.api.TemplatesApi;
import com.docusign.esign.client.ApiClient;

/**
 * Test seam: produces a configured {@link TemplatesApi} for a given access token.
 * Implementations are responsible for constructing the underlying {@link ApiClient}
 * with the right base path and Authorization header.
 */
interface TemplatesApiFactory {

	TemplatesApi create(String basePath, String accessToken);
}
