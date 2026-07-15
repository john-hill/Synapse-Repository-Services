package org.sagebionetworks;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;
import software.amazon.awssdk.services.ses.model.SendEmailResponse;
import software.amazon.awssdk.services.ses.model.SendRawEmailRequest;
import software.amazon.awssdk.services.ses.model.SendRawEmailResponse;

/**
 * Factory for a Mockito mock of Amazon's SES client, used to prevent messages
 * from being sent during testing.
 */
public class StubSesClientFactory {

	public static SesClient create() {
		SesClient mock = mock(SesClient.class);
		when(mock.sendEmail(any(SendEmailRequest.class))).thenReturn(SendEmailResponse.builder().build());
		when(mock.sendRawEmail(any(SendRawEmailRequest.class))).thenReturn(SendRawEmailResponse.builder().build());
		return mock;
	}

}
