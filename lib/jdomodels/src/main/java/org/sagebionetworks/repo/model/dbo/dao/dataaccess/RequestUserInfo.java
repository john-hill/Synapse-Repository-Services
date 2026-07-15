package org.sagebionetworks.repo.model.dbo.dao.dataaccess;

import java.util.Date;
import java.util.Objects;

public class RequestUserInfo {

	private String requestId;
	private String accessRequirementId;
	private String accessRequirementName;
	private String submissionStatus;
	private Boolean isEDuc;
	private String envelopeId;
	private Date submittedOn;
	private Date modifiedOn;

	public String getRequestId() {
		return requestId;
	}

	public void setRequestId(String requestId) {
		this.requestId = requestId;
	}

	public String getAccessRequirementId() {
		return accessRequirementId;
	}

	public void setAccessRequirementId(String accessRequirementId) {
		this.accessRequirementId = accessRequirementId;
	}

	public String getAccessRequirementName() {
		return accessRequirementName;
	}

	public void setAccessRequirementName(String accessRequirementName) {
		this.accessRequirementName = accessRequirementName;
	}

	public String getSubmissionStatus() {
		return submissionStatus;
	}

	public void setSubmissionStatus(String submissionStatus) {
		this.submissionStatus = submissionStatus;
	}

	public Boolean getIsEDuc() {
		return isEDuc;
	}

	public void setIsEDuc(Boolean isEDuc) {
		this.isEDuc = isEDuc;
	}

	public String getEnvelopeId() {
		return envelopeId;
	}

	public void setEnvelopeId(String envelopeId) {
		this.envelopeId = envelopeId;
	}

	public Date getSubmittedOn() {
		return submittedOn;
	}

	public void setSubmittedOn(Date submittedOn) {
		this.submittedOn = submittedOn;
	}

	public Date getModifiedOn() {
		return modifiedOn;
	}

	public void setModifiedOn(Date modifiedOn) {
		this.modifiedOn = modifiedOn;
	}

	@Override
	public int hashCode() {
		return Objects.hash(requestId, accessRequirementId, accessRequirementName,
				submissionStatus, isEDuc, submittedOn, modifiedOn);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) return true;
		if (obj == null || getClass() != obj.getClass()) return false;
		RequestUserInfo other = (RequestUserInfo) obj;
		return Objects.equals(requestId, other.requestId)
				&& Objects.equals(accessRequirementId, other.accessRequirementId)
				&& Objects.equals(accessRequirementName, other.accessRequirementName)
				&& Objects.equals(submissionStatus, other.submissionStatus)
				&& Objects.equals(isEDuc, other.isEDuc)
				&& Objects.equals(submittedOn, other.submittedOn)
				&& Objects.equals(modifiedOn, other.modifiedOn);
	}
}
