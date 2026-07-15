package org.sagebionetworks.repo.manager;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayInputStream;
import java.util.Properties;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;
import software.amazon.awssdk.services.ses.model.SendEmailResponse;
import software.amazon.awssdk.services.ses.model.SendRawEmailRequest;
import software.amazon.awssdk.services.ses.model.SendRawEmailResponse;

/**
 * Factory for a Mockito mock of Amazon's SES client.
 * Used to prevent messages from being sent during testing. A message whose
 * subject contains {@link #MESSAGE_SUBJECT_FOR_FAILURE} triggers a simulated
 * transmission failure.
 */
public class StubSesClient {

	public static final String MESSAGE_SUBJECT_FOR_FAILURE = "generatefailure";
	public static final String TRANSMISSION_FAILURE = "transmission failure";

	public static SesClient create() {
		SesClient mock = mock(SesClient.class);

		doAnswer(invocation -> {
			SendRawEmailRequest request = invocation.getArgument(0);
			try {
				MimeMessage mimeMessage = new MimeMessage(Session.getDefaultInstance(new Properties()),
						new ByteArrayInputStream(request.rawMessage().data().asByteArray()));
				if (mimeMessage.getSubject().toLowerCase().indexOf(MESSAGE_SUBJECT_FOR_FAILURE) >= 0) {
					throw new RuntimeException(TRANSMISSION_FAILURE);
				}
			} catch (MessagingException e) {
				throw new RuntimeException(e);
			}
			return SendRawEmailResponse.builder().build();
		}).when(mock).sendRawEmail(any(SendRawEmailRequest.class));

		doAnswer(invocation -> {
			SendEmailRequest request = invocation.getArgument(0);
			if (request.message().subject().data().toLowerCase().indexOf(MESSAGE_SUBJECT_FOR_FAILURE) >= 0) {
				throw new RuntimeException(TRANSMISSION_FAILURE);
			}
			return SendEmailResponse.builder().build();
		}).when(mock).sendEmail(any(SendEmailRequest.class));

		return mock;
	}

}
