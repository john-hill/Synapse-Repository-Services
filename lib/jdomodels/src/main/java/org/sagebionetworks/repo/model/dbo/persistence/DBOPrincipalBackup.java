package org.sagebionetworks.repo.model.dbo.persistence;

import java.util.Date;

/**
 * This is a backup object that is a union of the old and new fields of a Principal object.
 * 
 * @author John
 *
 */
public class DBOPrincipalBackup {

	private Long id;
	private Date creationDate;
	private Boolean isIndividual = false;
	private String etag;
	private String principalNameUnique;
	private String principalDisplay;
	private Boolean mustProvideNewPrincipalName;
	// Emails will be migrated out of this table in the future.
	@Deprecated 
	private String email;
	// Name has been broken out into name two categories, principalName and email and should no longer be used.
	@Deprecated
	private String name;
	public Long getId() {
		return id;
	}
	public void setId(Long id) {
		this.id = id;
	}
	public Date getCreationDate() {
		return creationDate;
	}
	public void setCreationDate(Date creationDate) {
		this.creationDate = creationDate;
	}
	public Boolean getIsIndividual() {
		return isIndividual;
	}
	public void setIsIndividual(Boolean isIndividual) {
		this.isIndividual = isIndividual;
	}
	public String getEtag() {
		return etag;
	}
	public void setEtag(String etag) {
		this.etag = etag;
	}
	public String getPrincipalNameUnique() {
		return principalNameUnique;
	}
	public void setPrincipalNameUnique(String principalNameLower) {
		this.principalNameUnique = principalNameLower;
	}
	public String getPrincipalDisplay() {
		return principalDisplay;
	}
	public void setPrincipalDisplay(String principalDisplay) {
		this.principalDisplay = principalDisplay;
	}
	public Boolean getMustProvideNewPrincipalName() {
		return mustProvideNewPrincipalName;
	}
	public void setMustProvideNewPrincipalName(Boolean mustProvideNewPrincipalName) {
		this.mustProvideNewPrincipalName = mustProvideNewPrincipalName;
	}
	public String getEmail() {
		return email;
	}
	public void setEmail(String email) {
		this.email = email;
	}
	public String getName() {
		return name;
	}
	public void setName(String name) {
		this.name = name;
	}
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result
				+ ((creationDate == null) ? 0 : creationDate.hashCode());
		result = prime * result + ((email == null) ? 0 : email.hashCode());
		result = prime * result + ((etag == null) ? 0 : etag.hashCode());
		result = prime * result + ((id == null) ? 0 : id.hashCode());
		result = prime * result
				+ ((isIndividual == null) ? 0 : isIndividual.hashCode());
		result = prime
				* result
				+ ((mustProvideNewPrincipalName == null) ? 0
						: mustProvideNewPrincipalName.hashCode());
		result = prime * result + ((name == null) ? 0 : name.hashCode());
		result = prime
				* result
				+ ((principalDisplay == null) ? 0 : principalDisplay.hashCode());
		result = prime
				* result
				+ ((principalNameUnique == null) ? 0 : principalNameUnique
						.hashCode());
		return result;
	}
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		DBOPrincipalBackup other = (DBOPrincipalBackup) obj;
		if (creationDate == null) {
			if (other.creationDate != null)
				return false;
		} else if (!creationDate.equals(other.creationDate))
			return false;
		if (email == null) {
			if (other.email != null)
				return false;
		} else if (!email.equals(other.email))
			return false;
		if (etag == null) {
			if (other.etag != null)
				return false;
		} else if (!etag.equals(other.etag))
			return false;
		if (id == null) {
			if (other.id != null)
				return false;
		} else if (!id.equals(other.id))
			return false;
		if (isIndividual == null) {
			if (other.isIndividual != null)
				return false;
		} else if (!isIndividual.equals(other.isIndividual))
			return false;
		if (mustProvideNewPrincipalName == null) {
			if (other.mustProvideNewPrincipalName != null)
				return false;
		} else if (!mustProvideNewPrincipalName
				.equals(other.mustProvideNewPrincipalName))
			return false;
		if (name == null) {
			if (other.name != null)
				return false;
		} else if (!name.equals(other.name))
			return false;
		if (principalDisplay == null) {
			if (other.principalDisplay != null)
				return false;
		} else if (!principalDisplay.equals(other.principalDisplay))
			return false;
		if (principalNameUnique == null) {
			if (other.principalNameUnique != null)
				return false;
		} else if (!principalNameUnique.equals(other.principalNameUnique))
			return false;
		return true;
	}
	@Override
	public String toString() {
		return "DBOPrincipalBackup [id=" + id + ", creationDate="
				+ creationDate + ", isIndividual=" + isIndividual + ", etag="
				+ etag + ", principalNameLower=" + principalNameUnique
				+ ", principalDisplay=" + principalDisplay
				+ ", mustProvideNewPrincipalName="
				+ mustProvideNewPrincipalName + ", email=" + email + ", name="
				+ name + "]";
	}
	
}
