package org.sagebionetworks.docusign;

import java.util.List;

import com.docusign.esign.model.Envelope;
import com.docusign.esign.model.EnvelopeDefinition;
import com.docusign.esign.model.EnvelopeSummary;
import com.docusign.esign.model.Recipients;

interface DocuSignEnvelopesApi {

	EnvelopeSummary createEnvelope(EnvelopeDefinition envelopeDefinition);

	void voidEnvelope(String envelopeId, String reason);

	void updateEnvelope(String envelopeId, Envelope envelope);

	Envelope getEnvelope(String envelopeId);

	List<Envelope> listStatus(List<String> envelopeIds);

	byte[] getDocument(String envelopeId, String documentId);

	/**
	 * Remove the given recipients from an envelope.
	 */
	void deleteRecipients(String envelopeId, Recipients recipients);
}
