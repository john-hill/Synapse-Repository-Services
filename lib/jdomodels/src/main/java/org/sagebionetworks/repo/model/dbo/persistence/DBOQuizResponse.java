package org.sagebionetworks.repo.model.dbo.persistence;

import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_QUIZ_RESPONSE_CREATED_BY;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_QUIZ_RESPONSE_CREATED_ON;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_QUIZ_RESPONSE_ETAG;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_QUIZ_RESPONSE_ID;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_QUIZ_RESPONSE_PASSED;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_QUIZ_RESPONSE_PASSING_JSON;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_QUIZ_RESPONSE_QUIZ_ID;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_QUIZ_RESPONSE_RESPONSE_JSON;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_QUIZ_RESPONSE_REVOKED_ON;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_QUIZ_RESPONSE_SCORE;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.DDL_QUIZ_RESPONSE;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.TABLE_QUIZ_RESPONSE;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

import org.sagebionetworks.repo.model.dbo.FieldColumn;
import org.sagebionetworks.repo.model.dbo.MigratableDatabaseObject;
import org.sagebionetworks.repo.model.dbo.TableMapping;
import org.sagebionetworks.repo.model.dbo.migration.BasicMigratableTableTranslation;
import org.sagebionetworks.repo.model.dbo.migration.MigratableTableTranslation;
import org.sagebionetworks.repo.model.migration.MigrationType;

public class DBOQuizResponse implements MigratableDatabaseObject<DBOQuizResponse, DBOQuizResponse> {

	private static FieldColumn[] FIELDS = new FieldColumn[] {
			new FieldColumn("id", COL_QUIZ_RESPONSE_ID).withIsPrimaryKey(true).withIsBackupId(true),
			new FieldColumn("etag", COL_QUIZ_RESPONSE_ETAG).withIsEtag(true),
			new FieldColumn("createdBy", COL_QUIZ_RESPONSE_CREATED_BY),
			new FieldColumn("createdOn", COL_QUIZ_RESPONSE_CREATED_ON),
			new FieldColumn("revokedOn", COL_QUIZ_RESPONSE_REVOKED_ON),
			new FieldColumn("quizId", COL_QUIZ_RESPONSE_QUIZ_ID),
			new FieldColumn("score", COL_QUIZ_RESPONSE_SCORE),
			new FieldColumn("passed", COL_QUIZ_RESPONSE_PASSED),
			new FieldColumn("responseJson", COL_QUIZ_RESPONSE_RESPONSE_JSON),
			new FieldColumn("passingJson", COL_QUIZ_RESPONSE_PASSING_JSON), };

	private Long id;
	private String etag;
	private Long createdBy;
	private Long createdOn;
	private Long revokedOn;
	private Long quizId;
	private Long score;
	private Boolean passed;
	private String responseJson;
	private String passingJson;

	@Override
	public TableMapping<DBOQuizResponse> getTableMapping() {
		return new TableMapping<DBOQuizResponse>() {

			@Override
			public DBOQuizResponse mapRow(ResultSet rs, int rowNum) throws SQLException {
				DBOQuizResponse dbo = new DBOQuizResponse();
				dbo.setId(rs.getLong(COL_QUIZ_RESPONSE_ID));
				dbo.setEtag(rs.getString(COL_QUIZ_RESPONSE_ETAG));
				dbo.setCreatedBy(rs.getLong(COL_QUIZ_RESPONSE_CREATED_BY));
				dbo.setCreatedOn(rs.getLong(COL_QUIZ_RESPONSE_CREATED_ON));
				dbo.setRevokedOn(rs.getLong(COL_QUIZ_RESPONSE_REVOKED_ON));
				if (rs.wasNull()) {
					dbo.setRevokedOn(null);
				}
				dbo.setQuizId(rs.getLong(COL_QUIZ_RESPONSE_QUIZ_ID));
				dbo.setScore(rs.getLong(COL_QUIZ_RESPONSE_SCORE));
				dbo.setPassed(rs.getBoolean(COL_QUIZ_RESPONSE_PASSED));
				dbo.setResponseJson(rs.getString(COL_QUIZ_RESPONSE_RESPONSE_JSON));
				dbo.setPassingJson(rs.getString(COL_QUIZ_RESPONSE_PASSING_JSON));
				return dbo;
			}

			@Override
			public String getTableName() {
				return TABLE_QUIZ_RESPONSE;
			}

			@Override
			public FieldColumn[] getFieldColumns() {
				return FIELDS;
			}

			@Override
			public String getDDLFileName() {
				return DDL_QUIZ_RESPONSE;
			}

			@Override
			public Class<? extends DBOQuizResponse> getDBOClass() {
				return DBOQuizResponse.class;
			}
		};
	}

	@Override
	public MigrationType getMigratableTableType() {
		return MigrationType.QUIZ_RESPONSE;
	}

	@Override
	public MigratableTableTranslation<DBOQuizResponse, DBOQuizResponse> getTranslator() {
		return new BasicMigratableTableTranslation<>();
	}

	@Override
	public Class<? extends DBOQuizResponse> getBackupClass() {
		return DBOQuizResponse.class;
	}

	@Override
	public Class<? extends DBOQuizResponse> getDatabaseObjectClass() {
		return DBOQuizResponse.class;
	}

	@Override
	public List<MigratableDatabaseObject<?, ?>> getSecondaryTypes() {
		return null;
	}

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getEtag() {
		return etag;
	}

	public void setEtag(String etag) {
		this.etag = etag;
	}

	public Long getCreatedBy() {
		return createdBy;
	}

	public void setCreatedBy(Long createdBy) {
		this.createdBy = createdBy;
	}

	public Long getCreatedOn() {
		return createdOn;
	}

	public void setCreatedOn(Long createdOn) {
		this.createdOn = createdOn;
	}

	public Long getRevokedOn() {
		return revokedOn;
	}

	public void setRevokedOn(Long revokedOn) {
		this.revokedOn = revokedOn;
	}

	public Long getQuizId() {
		return quizId;
	}

	public void setQuizId(Long quizId) {
		this.quizId = quizId;
	}

	public Long getScore() {
		return score;
	}

	public void setScore(Long score) {
		this.score = score;
	}

	public Boolean getPassed() {
		return passed;
	}

	public void setPassed(Boolean passed) {
		this.passed = passed;
	}

	public String getResponseJson() {
		return responseJson;
	}

	public void setResponseJson(String responseJson) {
		this.responseJson = responseJson;
	}

	public String getPassingJson() {
		return passingJson;
	}

	public void setPassingJson(String passingJson) {
		this.passingJson = passingJson;
	}

	@Override
	public int hashCode() {
		return Objects.hash(createdBy, createdOn, etag, id, passed, passingJson, quizId, responseJson, revokedOn,
				score);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		DBOQuizResponse other = (DBOQuizResponse) obj;
		return Objects.equals(createdBy, other.createdBy) && Objects.equals(createdOn, other.createdOn)
				&& Objects.equals(etag, other.etag) && Objects.equals(id, other.id)
				&& Objects.equals(passed, other.passed) && Objects.equals(passingJson, other.passingJson)
				&& Objects.equals(quizId, other.quizId) && Objects.equals(responseJson, other.responseJson)
				&& Objects.equals(revokedOn, other.revokedOn) && Objects.equals(score, other.score);
	}

	@Override
	public String toString() {
		return "DBOQuizResponse [id=" + id + ", etag=" + etag + ", createdBy=" + createdBy + ", createdOn=" + createdOn
				+ ", revokedOn=" + revokedOn + ", quizId=" + quizId + ", score=" + score + ", passed=" + passed
				+ ", responseJson=" + responseJson + ", passingJson=" + passingJson + "]";
	}

}
