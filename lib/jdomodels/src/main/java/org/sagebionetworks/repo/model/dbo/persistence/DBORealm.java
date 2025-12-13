/**
 * 
 */
package org.sagebionetworks.repo.model.dbo.persistence;

import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_REALM_ADMINISTRATOR;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_REALM_ANONYMOUS;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_REALM_AUTHENTICATED;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_REALM_CREATED_ON;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_REALM_E_TAG;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_REALM_ID;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_REALM_NAME;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_REALM_PUBLIC;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.DDL_FILE_REALM;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.TABLE_REALM;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.sagebionetworks.repo.model.dbo.FieldColumn;
import org.sagebionetworks.repo.model.dbo.MigratableDatabaseObject;
import org.sagebionetworks.repo.model.dbo.TableMapping;
import org.sagebionetworks.repo.model.dbo.migration.BasicMigratableTableTranslation;
import org.sagebionetworks.repo.model.dbo.migration.MigratableTableTranslation;
import org.sagebionetworks.repo.model.migration.MigrationType;

/**
 * @author brucehoff
 *
 */
public class DBORealm implements MigratableDatabaseObject<DBORealm, DBORealm> {
	private Long id;
	private Date creationDate;
	private String etag;
	private String name;
	private Long anonymousUserId;
	private Long publicGroup;
	private Long authenticatedUsers;
	private Long administrativeGroup;


	private static FieldColumn[] FIELDS = new FieldColumn[] {
		new FieldColumn("id", COL_REALM_ID, true).withIsBackupId(true),
		new FieldColumn("etag", COL_REALM_E_TAG).withIsEtag(true),
		new FieldColumn("creationDate", COL_REALM_CREATED_ON),
		new FieldColumn("name", COL_REALM_NAME),
		new FieldColumn("anonymousUserId", COL_REALM_ANONYMOUS),
		new FieldColumn("publicGroup", COL_REALM_PUBLIC),
		new FieldColumn("authenticatedUsers", COL_REALM_AUTHENTICATED),
		new FieldColumn("administrativeGroup", COL_REALM_ADMINISTRATOR)
		};

	@Override
	public TableMapping<DBORealm> getTableMapping() {
		return new TableMapping<DBORealm>() {
			// Map a result set to this object
			@Override
			public DBORealm mapRow(ResultSet rs, int rowNum) throws SQLException {
				DBORealm realm = new DBORealm();
				realm.setId(rs.getLong(COL_REALM_ID));
				Timestamp ts = rs.getTimestamp(COL_REALM_CREATED_ON);
				realm.setCreationDate(new Date(ts.getTime()));
				realm.setName(rs.getString(COL_REALM_NAME));
				realm.setEtag(rs.getString(COL_REALM_E_TAG));
				realm.setAnonymousUserId(rs.getLong(COL_REALM_ANONYMOUS));
				realm.setPublicGroup(rs.getLong(COL_REALM_PUBLIC));
				realm.setAuthenticatedUsers(rs.getLong(COL_REALM_AUTHENTICATED));
				realm.setAdministrativeGroup(rs.getLong(COL_REALM_ADMINISTRATOR));
				return realm;
			}

			@Override
			public String getTableName() {
				return TABLE_REALM;
			}

			@Override
			public String getDDLFileName() {
				return DDL_FILE_REALM;
			}

			@Override
			public FieldColumn[] getFieldColumns() {
				return FIELDS;
			}

			@Override
			public Class<? extends DBORealm> getDBOClass() {
				return DBORealm.class;
			}
		};
	}

	@Override
	public MigrationType getMigratableTableType() {
		return MigrationType.REALM;
	}

	@Override
	public Class<? extends DBORealm> getBackupClass() {
		return DBORealm.class;
	}

	@Override
	public Class<? extends DBORealm> getDatabaseObjectClass() {
		return DBORealm.class;
	}

	@Override
	public List<MigratableDatabaseObject<?,?>> getSecondaryTypes() {
		return Collections.singletonList(new DBORealmIdentityProvider());
	}

	@Override
	public MigratableTableTranslation<DBORealm, DBORealm> getTranslator() {
		return new BasicMigratableTableTranslation<DBORealm>();
	}

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

	public String getEtag() {
		return etag;
	}

	public void setEtag(String etag) {
		this.etag = etag;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public Long getAnonymousUserId() {
		return anonymousUserId;
	}

	public void setAnonymousUserId(Long anonymousUserId) {
		this.anonymousUserId = anonymousUserId;
	}

	public Long getPublicGroup() {
		return publicGroup;
	}

	public void setPublicGroup(Long publicGroup) {
		this.publicGroup = publicGroup;
	}

	public Long getAuthenticatedUsers() {
		return authenticatedUsers;
	}

	public void setAuthenticatedUsers(Long authenticatedUsers) {
		this.authenticatedUsers = authenticatedUsers;
	}

	public Long getAdministrativeGroup() {
		return administrativeGroup;
	}

	public void setAdministrativeGroup(Long administrativeGroup) {
		this.administrativeGroup = administrativeGroup;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((administrativeGroup == null) ? 0 : administrativeGroup.hashCode());
		result = prime * result + ((anonymousUserId == null) ? 0 : anonymousUserId.hashCode());
		result = prime * result + ((authenticatedUsers == null) ? 0 : authenticatedUsers.hashCode());
		result = prime * result + ((creationDate == null) ? 0 : creationDate.hashCode());
		result = prime * result + ((etag == null) ? 0 : etag.hashCode());
		result = prime * result + ((id == null) ? 0 : id.hashCode());
		result = prime * result + ((name == null) ? 0 : name.hashCode());
		result = prime * result + ((publicGroup == null) ? 0 : publicGroup.hashCode());
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
		DBORealm other = (DBORealm) obj;
		if (administrativeGroup == null) {
			if (other.administrativeGroup != null)
				return false;
		} else if (!administrativeGroup.equals(other.administrativeGroup))
			return false;
		if (anonymousUserId == null) {
			if (other.anonymousUserId != null)
				return false;
		} else if (!anonymousUserId.equals(other.anonymousUserId))
			return false;
		if (authenticatedUsers == null) {
			if (other.authenticatedUsers != null)
				return false;
		} else if (!authenticatedUsers.equals(other.authenticatedUsers))
			return false;
		if (creationDate == null) {
			if (other.creationDate != null)
				return false;
		} else if (!creationDate.equals(other.creationDate))
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
		if (name == null) {
			if (other.name != null)
				return false;
		} else if (!name.equals(other.name))
			return false;
		if (publicGroup == null) {
			if (other.publicGroup != null)
				return false;
		} else if (!publicGroup.equals(other.publicGroup))
			return false;
		return true;
	}


}
