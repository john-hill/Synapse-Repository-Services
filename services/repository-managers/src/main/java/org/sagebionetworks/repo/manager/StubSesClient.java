package org.sagebionetworks.repo.manager;

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
 * Stub implementation of Amazon's SES client
 * Used to prevent messages from being sent during testing
 */
public class StubSesClient implements SesClient {

	public static final String MESSAGE_SUBJECT_FOR_FAILURE = "generatefailure";
	public static final String TRANSMISSION_FAILURE = "transmission failure";

	@Override
	public String serviceName() {
		return SesClient.SERVICE_NAME;
	}

	@Override
	public void close() {
	}

	@Override
	public SendRawEmailResponse sendRawEmail(SendRawEmailRequest sendRawEmailRequest) {
		try {
			MimeMessage mimeMessage = new MimeMessage(Session.getDefaultInstance(new Properties()),
					new ByteArrayInputStream(sendRawEmailRequest.rawMessage().data().asByteArray()));
			if (mimeMessage.getSubject().toLowerCase().indexOf(MESSAGE_SUBJECT_FOR_FAILURE)>=0) {
				throw new RuntimeException(TRANSMISSION_FAILURE);
			}
		} catch (MessagingException e) {
			throw new RuntimeException(e);
		}
		return SendRawEmailResponse.builder().build();
	}

	@Override
	public SendEmailResponse sendEmail(SendEmailRequest sendEmailRequest) {
		if (sendEmailRequest.message().subject().data().toLowerCase().indexOf(MESSAGE_SUBJECT_FOR_FAILURE)>=0) {
			throw new RuntimeException(TRANSMISSION_FAILURE);
		}
		return SendEmailResponse.builder().build();
	}

}
