package org.sagebionetworks.repo.model.dbo.schema;

import java.util.Objects;

import org.sagebionetworks.repo.model.schema.ValidationSummaryStatistics;

public class RecordSetValidationResult {
	
	private ValidationSummaryStatistics summaryStatistics;
	private String detailsFileHandleId;

	public RecordSetValidationResult(ValidationSummaryStatistics summaryStatistics, String detailsFileHandleId) {
		this.summaryStatistics = summaryStatistics;
		this.detailsFileHandleId = detailsFileHandleId;
	}
	
	public ValidationSummaryStatistics getSummaryStatistics() {
		return summaryStatistics;
	}
	
	public String getDetailsFileHandleId() {
		return detailsFileHandleId;
	}

	@Override
	public int hashCode() {
		return Objects.hash(detailsFileHandleId, summaryStatistics);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null) {
			return false;
		}
		if (getClass() != obj.getClass()) {
			return false;
		}
		RecordSetValidationResult other = (RecordSetValidationResult) obj;
		return Objects.equals(detailsFileHandleId, other.detailsFileHandleId) && Objects.equals(summaryStatistics, other.summaryStatistics);
	}

}
