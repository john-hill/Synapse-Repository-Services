package org.sagebionetworks.repo.model.dbo.persistence.discussion;

import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_DISCUSSION_THREAD_SUBMISSION_REFERENCE_SUBMISSION_ID;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_DISCUSSION_THREAD_SUBMISSION_REFERENCE_THREAD_ID;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.DDL_DISCUSSION_THREAD_SUBMISSION_REFERENCE;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.TABLE_DISCUSSION_THREAD_SUBMISSION_REFERENCE;

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


public class DBODiscussionThreadSubmissionReference implements MigratableDatabaseObject<DBODiscussionThreadSubmissionReference, DBODiscussionThreadSubmissionReference> {

	private static final FieldColumn[] FIELDS = new FieldColumn[] {
		new FieldColumn("threadId", COL_DISCUSSION_THREAD_SUBMISSION_REFERENCE_THREAD_ID, true).withIsBackupId(true),
		new FieldColumn("submissionId", COL_DISCUSSION_THREAD_SUBMISSION_REFERENCE_SUBMISSION_ID, false),
	};

	private Long threadId;
	private Long submissionId;

	public Long getThreadId() {
		return threadId;
	}

	public void setThreadId(Long threadId) {
		this.threadId = threadId;
	}

	public Long getSubmissionId() {
		return submissionId;
	}

	public void setSubmissionId(Long submissionId) {
		this.submissionId = submissionId;
	}

	@Override
	public int hashCode() {
		return Objects.hash(threadId, submissionId);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null || getClass() != obj.getClass())
			return false;
		DBODiscussionThreadSubmissionReference other = (DBODiscussionThreadSubmissionReference) obj;
		return Objects.equals(threadId, other.threadId) && Objects.equals(submissionId, other.submissionId);
	}

	@Override
	public String toString() {
		return "DBODiscussionThreadSubmissionReference [threadId=" + threadId + ", submissionId=" + submissionId + "]";
	}

	@Override
	public TableMapping<DBODiscussionThreadSubmissionReference> getTableMapping() {
		return new TableMapping<DBODiscussionThreadSubmissionReference>() {

			@Override
			public DBODiscussionThreadSubmissionReference mapRow(ResultSet rs, int rowNum) throws SQLException {
				DBODiscussionThreadSubmissionReference dbo = new DBODiscussionThreadSubmissionReference();
				dbo.setThreadId(rs.getLong(COL_DISCUSSION_THREAD_SUBMISSION_REFERENCE_THREAD_ID));
				dbo.setSubmissionId(rs.getLong(COL_DISCUSSION_THREAD_SUBMISSION_REFERENCE_SUBMISSION_ID));
				return dbo;
			}

			@Override
			public String getTableName() {
				return TABLE_DISCUSSION_THREAD_SUBMISSION_REFERENCE;
			}

			@Override
			public String getDDLFileName() {
				return DDL_DISCUSSION_THREAD_SUBMISSION_REFERENCE;
			}

			@Override
			public FieldColumn[] getFieldColumns() {
				return FIELDS;
			}

			@Override
			public Class<? extends DBODiscussionThreadSubmissionReference> getDBOClass() {
				return DBODiscussionThreadSubmissionReference.class;
			}
		};
	}

	@Override
	public MigrationType getMigratableTableType() {
		return MigrationType.DISCUSSION_THREAD_SUBMISSION_REFERENCE;
	}

	@Override
	public MigratableTableTranslation<DBODiscussionThreadSubmissionReference, DBODiscussionThreadSubmissionReference> getTranslator() {
		return new BasicMigratableTableTranslation<DBODiscussionThreadSubmissionReference>();
	}

	@Override
	public Class<? extends DBODiscussionThreadSubmissionReference> getBackupClass() {
		return DBODiscussionThreadSubmissionReference.class;
	}

	@Override
	public Class<? extends DBODiscussionThreadSubmissionReference> getDatabaseObjectClass() {
		return DBODiscussionThreadSubmissionReference.class;
	}

	@Override
	public List<MigratableDatabaseObject<?, ?>> getSecondaryTypes() {
		return null;
	}
}
