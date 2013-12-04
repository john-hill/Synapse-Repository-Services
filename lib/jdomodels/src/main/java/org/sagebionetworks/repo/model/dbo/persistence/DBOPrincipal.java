/**
 * 
 */
package org.sagebionetworks.repo.model.dbo.persistence;

import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.*;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.TABLE_PRINCIPAL;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

import org.sagebionetworks.repo.model.dbo.FieldColumn;
import org.sagebionetworks.repo.model.dbo.MigratableDatabaseObject;
import org.sagebionetworks.repo.model.dbo.TableMapping;
import org.sagebionetworks.repo.model.dbo.dao.PrincipalUtils;
import org.sagebionetworks.repo.model.dbo.migration.MigratableTableTranslation;
import org.sagebionetworks.repo.model.migration.MigrationType;

/**
 * This table represents all Principals in Synapse including users and teams (groups).
 * Every principal has a unique principal name that can be used for disambiguation and 
 * a level of anonymity.
 * This principal name can be used to log-on (get a session token).
 * 
 *
 */
public class DBOPrincipal implements MigratableDatabaseObject<DBOPrincipal, DBOPrincipalBackup> {
	
	private Long id;
	private Date creationDate;
	private Boolean isIndividual = false;
	private String etag;
	// This is the unique case-insensitive name of a principal.
	// Two principals cannot have the same name that differs only by case.  Also only letters and numbers contribute to the uniqueness.
	private String principalNameUnique;
	// This is how the user entered their principal name and can include both upper and lower case characters.
	// We keep this original name for display purposes only.
	private String principalNameDisplay;
	private Boolean mustProvideNewPrincipalName;
	// Since only users have an email we should migrate this out of this table.
	@Deprecated
	private String email;

	private static FieldColumn[] FIELDS = new FieldColumn[] {
		new FieldColumn("id", COL_PRINCIPAL_ID, true).withIsBackupId(true),
		new FieldColumn("creationDate", COL_PRINCIPAL_CREATION_DATE),
		new FieldColumn("isIndividual", COL_PRINCIPAL_IS_INDIVIDUAL), 
		new FieldColumn("etag", COL_PRINCIPAL_E_TAG).withIsEtag(true),
		new FieldColumn("principalNameUnique", COL_PRINCIPAL_PRINCIPAL_NAME_UNIQUE),
		new FieldColumn("principalNameDisplay", COL_PRINCIPAL_PRINCIPAL_NAME_DISPLAY),
		new FieldColumn("mustProvideNewPrincipalName", COL_PRINCIPAL_MUST_PROVIDE_NEW_PRICIPAL_NAME),
		new FieldColumn("email", COL_PRINCIPAL_EMAIL),
		};


	@Override
	public TableMapping<DBOPrincipal> getTableMapping() {
		return new TableMapping<DBOPrincipal>() {
			// Map a result set to this object
			@Override
			public DBOPrincipal mapRow(ResultSet rs, int rowNum) throws SQLException {
				DBOPrincipal ug = new DBOPrincipal();
				ug.setId(rs.getLong(COL_PRINCIPAL_ID));
				Timestamp ts = rs.getTimestamp(COL_PRINCIPAL_CREATION_DATE);
				ug.setCreationDate(new Date(ts.getTime()));
				ug.setIsIndividual(rs.getBoolean(COL_PRINCIPAL_IS_INDIVIDUAL));
				ug.setEtag(rs.getString(COL_PRINCIPAL_E_TAG));
				ug.setPrincipalNameUnique(rs.getString(COL_PRINCIPAL_PRINCIPAL_NAME_UNIQUE));
				ug.setPrincipalNameDisplay(rs.getString(COL_PRINCIPAL_PRINCIPAL_NAME_DISPLAY));
				ug.setMustProvideNewPrincipalName(rs.getBoolean(COL_PRINCIPAL_MUST_PROVIDE_NEW_PRICIPAL_NAME));
				return ug;
			}

			@Override
			public String getTableName() {
				return TABLE_PRINCIPAL;
			}

			@Override
			public String getDDLFileName() {
				return DDL_FILE_PRINCIPAL;
			}

			@Override
			public FieldColumn[] getFieldColumns() {
				return FIELDS;
			}

			@Override
			public Class<? extends DBOPrincipal> getDBOClass() {
				return DBOPrincipal.class;
			}
		};
	}


	/**
	 * @return the id
	 */
	public Long getId() {
		return id;
	}


	/**
	 * @param id the id to set
	 */
	public void setId(Long id) {
		this.id = id;
	}


	public String getPrincipalNameUnique() {
		return principalNameUnique;
	}


	public void setPrincipalNameUnique(String principalNameLower) {
		this.principalNameUnique = principalNameLower;
	}
	

	public String getPrincipalNameDisplay() {
		return principalNameDisplay;
	}


	public void setPrincipalNameDisplay(String principalNameDisplay) {
		this.principalNameDisplay = principalNameDisplay;
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


	/**
	 * @return the creationDate
	 */
	public Date getCreationDate() {
		return creationDate;
	}


	/**
	 * @param creationDate the creationDate to set
	 */
	public void setCreationDate(Date creationDate) {
		this.creationDate = creationDate;
	}


	/**
	 * @return the isIndividual
	 */
	public Boolean getIsIndividual() {
		return isIndividual;
	}


	/**
	 * @param isIndividual the isIndividual to set
	 */
	public void setIsIndividual(Boolean isIndividual) {
		this.isIndividual = isIndividual;
	}
	
	/**
	 * @return the etag
	 */
	public String getEtag() {
		return etag;
	}
	
	/**
	 * @param etag the etag to set
	 */
	public void setEtag(String etag) {
		this.etag = etag;
	}


	@Override
	public MigrationType getMigratableTableType() {
		return MigrationType.PRINCIPAL;
	}


	@Override
	public MigratableTableTranslation<DBOPrincipal, DBOPrincipalBackup> getTranslator() {
		// We do not currently have a backup for this object.
		return new MigratableTableTranslation<DBOPrincipal, DBOPrincipalBackup>(){

			@Override
			public DBOPrincipal createDatabaseObjectFromBackup(
					DBOPrincipalBackup backup) {
				// The utility does the real work
				return PrincipalUtils.createDatabaseObjectFromBackup(backup);
			}

			@Override
			public DBOPrincipalBackup createBackupFromDatabaseObject(DBOPrincipal dbo) {
				// The utility does the real work.
				return PrincipalUtils.createBackupFromDatabaseObject(dbo);
			}};
	}


	@Override
	public Class<? extends DBOPrincipalBackup> getBackupClass() {
		return DBOPrincipalBackup.class;
	}


	@Override
	public Class<? extends DBOPrincipal> getDatabaseObjectClass() {
		return DBOPrincipal.class;
	}


	@Override
	public List<MigratableDatabaseObject> getSecondaryTypes() {
		List<MigratableDatabaseObject> list = new LinkedList<MigratableDatabaseObject>();
		list.add(new DBOGroupMembers());
		list.add(new DBOCredential());
		return list;
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
		result = prime
				* result
				+ ((principalNameDisplay == null) ? 0 : principalNameDisplay
						.hashCode());
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
		DBOPrincipal other = (DBOPrincipal) obj;
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
		if (principalNameDisplay == null) {
			if (other.principalNameDisplay != null)
				return false;
		} else if (!principalNameDisplay.equals(other.principalNameDisplay))
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
		return "DBOUserGroup [id=" + id + ", creationDate=" + creationDate
				+ ", isIndividual=" + isIndividual + ", etag=" + etag
				+ ", principalNameLower=" + principalNameUnique
				+ ", principalNameDisplay=" + principalNameDisplay
				+ ", mustProvideNewPrincipalName="
				+ mustProvideNewPrincipalName + ", email=" + email + "]";
	}


}
