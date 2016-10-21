package org.sagebionetworks.repo.model.dbo.dao.table;

import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.*;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.sagebionetworks.repo.model.ConflictingUpdateException;
import org.sagebionetworks.repo.model.dao.table.RowAccessor;
import org.sagebionetworks.repo.model.dao.table.RowHandler;
import org.sagebionetworks.repo.model.dao.table.RowSetAccessor;
import org.sagebionetworks.repo.model.dao.table.TableRowTruthDAO;
import org.sagebionetworks.repo.model.dbo.DBOBasicDao;
import org.sagebionetworks.repo.model.dbo.persistence.table.ColumnModelUtils;
import org.sagebionetworks.repo.model.dbo.persistence.table.DBOTableIdSequence;
import org.sagebionetworks.repo.model.dbo.persistence.table.DBOTableRowChange;
import org.sagebionetworks.repo.model.jdo.KeyFactory;
import org.sagebionetworks.repo.model.table.ColumnChange;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.IdRange;
import org.sagebionetworks.repo.model.table.PartialRow;
import org.sagebionetworks.repo.model.table.RawRowSet;
import org.sagebionetworks.repo.model.table.Row;
import org.sagebionetworks.repo.model.table.RowReference;
import org.sagebionetworks.repo.model.table.RowReferenceSet;
import org.sagebionetworks.repo.model.table.RowSet;
import org.sagebionetworks.repo.model.table.SparseChangeSetDto;
import org.sagebionetworks.repo.model.table.SparseRowDto;
import org.sagebionetworks.repo.model.table.TableChangeType;
import org.sagebionetworks.repo.model.table.TableRowChange;
import org.sagebionetworks.repo.transactions.WriteTransaction;
import org.sagebionetworks.repo.transactions.WriteTransactionReadCommitted;
import org.sagebionetworks.repo.web.NotFoundException;
import org.sagebionetworks.table.cluster.utils.TableModelUtils;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.S3Object;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

/**
 * Basic S3 & RDS implementation of the TableRowTruthDAO.
 * 
 * @author John
 * 
 */
public class TableRowTruthDAOImpl implements TableRowTruthDAO {
	
	public static final String SCAN_ROWS_TYPE_ERROR = "Can only scan over table changes of type: "+TableChangeType.ROW;

	private static Logger log = LogManager.getLogger(TableRowTruthDAOImpl.class);

	private static final String SQL_SELECT_VERSION_FOR_ETAG = "SELECT "
			+ COL_TABLE_ROW_VERSION + " FROM " + TABLE_ROW_CHANGE + " WHERE "
			+ COL_TABLE_ROW_TABLE_ID + " = ? AND " + COL_TABLE_ROW_TABLE_ETAG
			+ " = ? ";
	private static final String SQL_SELECT_MAX_ROWID = "SELECT " + COL_ID_SEQUENCE + " FROM " + TABLE_TABLE_ID_SEQUENCE + " WHERE "
			+ COL_ID_SEQUENCE_TABLE_ID + " = ?";
	
	private static final String SQL_SELECT_LAST_ROW_CHANGE_FOR_TABLE = "SELECT * FROM " + TABLE_ROW_CHANGE + " WHERE "
			+ COL_TABLE_ROW_TABLE_ID + " = ? ORDER BY " + COL_TABLE_ROW_VERSION + " DESC LIMIT 1";
	
	private static final String SQL_SELECT_LAST_ROW_CHANGE_FOR_TABLE_WITH_TYPE = "SELECT * FROM " + TABLE_ROW_CHANGE + " WHERE "
			+ COL_TABLE_ROW_TABLE_ID + " = ? AND "+COL_TABLE_ROW_TYPE+" = ? ORDER BY " + COL_TABLE_ROW_VERSION + " DESC LIMIT 1";
	
	
	private static final String SQL_SELECT_ROW_CHANGE_FOR_TABLE_AND_VERSION = "SELECT * FROM "
			+ TABLE_ROW_CHANGE
			+ " WHERE "
			+ COL_TABLE_ROW_TABLE_ID
			+ " = ? AND " + COL_TABLE_ROW_VERSION + " = ?";
	
	private static final String SQL_LIST_ALL_KEYS = "SELECT "
			+ COL_TABLE_ROW_KEY + " FROM " + TABLE_ROW_CHANGE+" WHERE "+COL_TABLE_ROW_KEY+" IS NOT NULL"
					+ " UNION SELECT "+COL_TABLE_ROW_KEY_NEW+ " FROM " + TABLE_ROW_CHANGE+" WHERE "+COL_TABLE_ROW_KEY_NEW+" IS NOT NULL";
	
	private static final String SQL_LIST_ALL_KEYS_FOR_TABLE = "SELECT "
			+ COL_TABLE_ROW_KEY + " FROM " + TABLE_ROW_CHANGE+" WHERE "+COL_TABLE_ROW_KEY+" IS NOT NULL AND "+COL_TABLE_ROW_TABLE_ID + " = ?"
					+ " UNION SELECT "+COL_TABLE_ROW_KEY_NEW+ " FROM " + TABLE_ROW_CHANGE+" WHERE "+COL_TABLE_ROW_KEY_NEW+" IS NOT NULL AND "+COL_TABLE_ROW_TABLE_ID + " = ?";
	
	private static final String SQL_SELECT_ALL_ROW_CHANGES_FOR_TABLE = "SELECT * FROM "
			+ TABLE_ROW_CHANGE
			+ " WHERE "
			+ COL_TABLE_ROW_TABLE_ID
			+ " = ? ORDER BY " + COL_TABLE_ROW_VERSION + " ASC";
	
	private static final String SQL_ALL_ROW_CHANGES_FOR_TABLE_GREATER_VERSION_BASE = "FROM "
			+ TABLE_ROW_CHANGE
			+ " WHERE "
			+ COL_TABLE_ROW_TABLE_ID
			+ " = ? AND "
			+ COL_TABLE_ROW_VERSION
			+ " > ? AND "+COL_TABLE_ROW_TYPE+" = '"+TableChangeType.ROW+"' ORDER BY "
			+ COL_TABLE_ROW_VERSION + " ASC";
	
	private static final String SQL_SELECT_ALL_ROW_CHANGES_FOR_TABLE_GREATER_VERSION = "SELECT * "
			+ SQL_ALL_ROW_CHANGES_FOR_TABLE_GREATER_VERSION_BASE;
	
	private static final String SQL_DELETE_ROW_DATA_FOR_TABLE = "DELETE FROM " + TABLE_TABLE_ID_SEQUENCE + " WHERE "
			+ COL_ID_SEQUENCE_TABLE_ID
			+ " = ?";
	private static final String KEY_TEMPLATE = "%1$s.csv.gz";
	private static final String SQL_TRUNCATE_SEQUENCE_TABLE = "DELETE FROM "
			+ TABLE_TABLE_ID_SEQUENCE + " WHERE " + COL_ID_SEQUENCE_TABLE_ID
			+ " > 0";
	private static final String SQL_SELECT_SEQUENCE_FOR_UPDATE = "SELECT * FROM "
			+ TABLE_TABLE_ID_SEQUENCE
			+ " WHERE "
			+ COL_ID_SEQUENCE_TABLE_ID
			+ " = ? FOR UPDATE";
	@Autowired
	private DBOBasicDao basicDao;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private AmazonS3Client s3Client;

	private String s3Bucket;

	RowMapper<DBOTableIdSequence> sequenceRowMapper = new DBOTableIdSequence()
			.getTableMapping();
	RowMapper<DBOTableRowChange> rowChangeMapper = new DBOTableRowChange()
			.getTableMapping();

	@WriteTransactionReadCommitted
	@Override
	public IdRange reserveIdsInRange(String tableIdString, long countToReserver) {
		if (tableIdString == null)
			throw new IllegalArgumentException("TableId cannot be null");
		long tableId = KeyFactory.stringToKey(tableIdString);

		// Setup the dbo
		DBOTableIdSequence dbo = null;
		boolean exists = false;
		// If this table already exists, lock the row and get the current value.
		Long currentSequence;
		Long currentVersion;
		try {
			// First lock row for this table
			dbo = jdbcTemplate.queryForObject(SQL_SELECT_SEQUENCE_FOR_UPDATE, sequenceRowMapper, tableId);
			currentSequence = dbo.getSequence();
			currentVersion = dbo.getVersionNumber();
			exists = true;
		} catch (EmptyResultDataAccessException e) {
			// This table does not exist yet
			currentSequence = -1l;
			currentVersion = -1l;
			exists = false;
		}
		// Create the new values
		dbo = new DBOTableIdSequence();
		dbo.setSequence(currentSequence + countToReserver);
		dbo.setTableId(tableId);
		dbo.setEtag(UUID.randomUUID().toString());
		dbo.setVersionNumber(currentVersion + 1);
		// create or update
		if (exists) {
			// update
			basicDao.update(dbo);
		} else {
			// create
			basicDao.createNew(dbo);
		}
		// Prepare the results
		IdRange range = new IdRange();
		if (countToReserver > 0) {
			range.setMaximumId(dbo.getSequence());
			range.setMinimumId(dbo.getSequence() - countToReserver + 1);
		}
		range.setMaximumUpdateId(currentSequence);
		range.setVersionNumber(dbo.getVersionNumber());
		range.setEtag(dbo.getEtag());
		return range;
	}

	/**
	 * Called after bean creation.
	 */
	public void initialize() {
		// Create the bucket as needed
		try {
			s3Client.createBucket(s3Bucket);
		} catch (AmazonS3Exception e) {
			log.info("S3 error creating bucket: " + e.getStackTrace());
		}
	}
	
	@WriteTransactionReadCommitted
	@Override
	public RowReferenceSet appendRowSetToTable(String userId, String tableId, List<ColumnModel> columns, final SparseChangeSetDto delta)
			throws IOException {
		// Now set the row version numbers and ID.
		int coutToReserver = TableModelUtils.countEmptyOrInvalidRowIds(delta);
		// Reserver IDs for the missing
		IdRange range = reserveIdsInRange(tableId, coutToReserver);
		delta.setEtag(range.getEtag());
		// Are any rows being updated?
		if (coutToReserver < delta.getRows().size()) {
			// Validate that this update does not contain any row level conflicts.
			checkForRowLevelConflict(tableId, delta);
		}
		// Now assign the rowIds and set the version number
		TableModelUtils.assignRowIdsAndVersionNumbers(delta, range);
		// We are ready to convert the file to a CSV and save it to S3.
		String key = saveToS3(new WriterCallback() {
			@Override
			public void write(OutputStream out) throws IOException {
				TableModelUtils.writeSparesChangeSetToGz(delta, out);
			}
		});
		// record the change
		DBOTableRowChange changeDBO = new DBOTableRowChange();
		changeDBO.setTableId(KeyFactory.stringToKey(tableId));
		changeDBO.setRowVersion(range.getVersionNumber());
		changeDBO.setEtag(range.getEtag());
		changeDBO.setCreatedBy(Long.parseLong(userId));
		changeDBO.setCreatedOn(System.currentTimeMillis());
		changeDBO.setKeyNew(key);
		changeDBO.setBucket(s3Bucket);
		changeDBO.setRowCount(new Long(delta.getRows().size()));
		changeDBO.setChangeType(TableChangeType.ROW.name());
		basicDao.createNew(changeDBO);

		// Prepare the results
		RowReferenceSet results = new RowReferenceSet();
		results.setHeaders(TableModelUtils.getSelectColumns(columns));
		results.setTableId(tableId);
		results.setEtag(changeDBO.getEtag());
		List<RowReference> refs = new LinkedList<RowReference>();
		// Build up the row references
		for (SparseRowDto row : delta.getRows()) {
			RowReference ref = new RowReference();
			ref.setRowId(row.getRowId());
			ref.setVersionNumber(range.getVersionNumber());
			refs.add(ref);
		}
		results.setRows(refs);
		return results;
	}
	
	@Override
	public long appendSchemaChangeToTable(String userId, String tableId,
			List<String> current, final List<ColumnChange> changes) throws IOException {
		
		long coutToReserver = 1;
		IdRange range = reserveIdsInRange(tableId, coutToReserver);
		// We are ready to convert the file to a CSV and save it to S3.
		String key = saveToS3(new WriterCallback() {
			@Override
			public void write(OutputStream out) throws IOException {
				ColumnModelUtils.writeSchemaChangeToGz(changes, out);
			}
		});
		// record the change
		DBOTableRowChange changeDBO = new DBOTableRowChange();
		changeDBO.setTableId(KeyFactory.stringToKey(tableId));
		changeDBO.setRowVersion(range.getVersionNumber());
		changeDBO.setEtag(range.getEtag());
		changeDBO.setColumnIds(TableModelUtils.createDelimitedColumnModelIdString(current));
		changeDBO.setCreatedBy(Long.parseLong(userId));
		changeDBO.setCreatedOn(System.currentTimeMillis());
		changeDBO.setKey(key);
		changeDBO.setBucket(s3Bucket);
		changeDBO.setRowCount(0L);
		changeDBO.setChangeType(TableChangeType.COLUMN.name());
		basicDao.createNew(changeDBO);
		return range.getVersionNumber();
	}
	
	/**
	 * Write the data from the given callback to S3.
	 * 
	 * @param callback
	 * @return
	 * @throws IOException
	 */
	String saveToS3(WriterCallback callback) throws IOException {
		// First write to a temp file.
		File temp = File.createTempFile("tempToS3", ".gz");
		FileOutputStream out = null;
		try {
			out = new FileOutputStream(temp);
			// write to the temp file.
			callback.write(out);
			out.flush();
			out.close();
			// upload it to S3.
			String key = String.format(KEY_TEMPLATE, UUID.randomUUID()
					.toString());
			s3Client.putObject(s3Bucket, key, temp);
			return key;
		} finally {
			if (out != null) {
				out.close();
			}
			if (temp != null) {
				temp.delete();
			}
		}
	}
	
	@Override
	public List<ColumnChange> getSchemaChangeForVersion(String tableId,
			long versionNumber) throws IOException {
		TableRowChange dto = getTableRowChange(tableId, versionNumber);
		// Download the file from S3
		S3Object object = s3Client.getObject(dto.getBucket(), dto.getKey());
		try {
			return ColumnModelUtils.readSchemaChangeFromGz(object.getObjectContent());
		} finally {
			// Need to close the stream unconditionally.
			object.getObjectContent().close();
		}
	}

	@Override
	public long getVersionForEtag(String tableIdString, String etag) {
		if (tableIdString == null)
			throw new IllegalArgumentException("TableId cannot be null");
		long tableId = KeyFactory.stringToKey(tableIdString);
		try {
			return jdbcTemplate.queryForObject(
					SQL_SELECT_VERSION_FOR_ETAG, new RowMapper<Long>() {
						@Override
						public Long mapRow(ResultSet rs, int rowNum)
								throws SQLException {
							return rs.getLong(COL_TABLE_ROW_VERSION);
						}
					}, tableId, etag);
		} catch (EmptyResultDataAccessException e) {
			throw new IllegalArgumentException("Invalid etag: " + etag);
		}
	}

	@Override
	public long getMaxRowId(String tableIdString) {
		if (tableIdString == null)
			throw new IllegalArgumentException("TableId cannot be null");
		long tableId = KeyFactory.stringToKey(tableIdString);
		try {
			return jdbcTemplate.queryForObject(SQL_SELECT_MAX_ROWID, Long.class, tableId);
		} catch (EmptyResultDataAccessException e) {
			// presumably, no rows have been added yet
			return 0L;
		}
	}

	@Override
	public TableRowChange getLastTableRowChange(String tableIdString) {
		if (tableIdString == null)
			throw new IllegalArgumentException("TableId cannot be null");
		long tableId = KeyFactory.stringToKey(tableIdString);
		try {
			DBOTableRowChange dbo = jdbcTemplate.queryForObject(SQL_SELECT_LAST_ROW_CHANGE_FOR_TABLE, rowChangeMapper, tableId);
			return TableRowChangeUtils.ceateDTOFromDBO(dbo);
		} catch (EmptyResultDataAccessException e) {
			// presumably, no rows have been added yet
			return null;
		}
	}
	
	@Override
	public TableRowChange getLastTableRowChange(String tableIdString,
			TableChangeType changeType) {
		ValidateArgument.required(tableIdString, "tableId");
		ValidateArgument.required(changeType, "TableChangeType");
		long tableId = KeyFactory.stringToKey(tableIdString);
		try {
			DBOTableRowChange dbo = jdbcTemplate.queryForObject(SQL_SELECT_LAST_ROW_CHANGE_FOR_TABLE_WITH_TYPE, rowChangeMapper, tableId, changeType.name());
			return TableRowChangeUtils.ceateDTOFromDBO(dbo);
		} catch (EmptyResultDataAccessException e) {
			// presumably, no rows have been added yet
			return null;
		}
	}

	/**
	 * List all changes for this table.
	 */
	@Override
	public List<TableRowChange> listRowSetsKeysForTable(String tableIdString) {
		if (tableIdString == null)
			throw new IllegalArgumentException("TableId cannot be null");
		long tableId = KeyFactory.stringToKey(tableIdString);
		List<DBOTableRowChange> dboList = jdbcTemplate.query(
				SQL_SELECT_ALL_ROW_CHANGES_FOR_TABLE, rowChangeMapper, tableId);
		return TableRowChangeUtils.ceateDTOFromDBO(dboList);
	}

	@Override
	public List<TableRowChange> listRowSetsKeysForTableGreaterThanVersion(
			String tableIdString, long versionNumber) {
		if (tableIdString == null)
			throw new IllegalArgumentException("TableId cannot be null");
		long tableId = KeyFactory.stringToKey(tableIdString);
		List<DBOTableRowChange> dboList = jdbcTemplate.query(
				SQL_SELECT_ALL_ROW_CHANGES_FOR_TABLE_GREATER_VERSION,
				rowChangeMapper, tableId, versionNumber);
		return TableRowChangeUtils.ceateDTOFromDBO(dboList);
	}

	@Override
	public TableRowChange getTableRowChange(String tableIdString,
			long rowVersion) throws NotFoundException {
		if (tableIdString == null)
			throw new IllegalArgumentException("TableID cannot be null");
		long tableId = KeyFactory.stringToKey(tableIdString);
		try {
			DBOTableRowChange dbo = jdbcTemplate.queryForObject(
					SQL_SELECT_ROW_CHANGE_FOR_TABLE_AND_VERSION,
					rowChangeMapper, tableId, rowVersion);
			return TableRowChangeUtils.ceateDTOFromDBO(dbo);
		} catch (EmptyResultDataAccessException e) {
			throw new NotFoundException(
					"TableRowChange does not exist for tableId: " + tableId
							+ " and row version: " + rowVersion);
		}
	}

	/**
	 * Read the RowSet from S3.
	 * 
	 * @throws NotFoundException
	 */
	@Override
	@Deprecated
	public RowSet getRowSet(String tableId, long rowVersion, List<ColumnModel> columns)
			throws IOException, NotFoundException {
		TableRowChange dto = getTableRowChange(tableId, rowVersion);
		// Download the file from S3
		S3Object object = s3Client.getObject(dto.getBucket(), dto.getKey());
		try {
			RowSet set = new RowSet();
			List<Row> rows = TableModelUtils.readFromCSVgzStream(object.getObjectContent());
			set.setTableId(tableId);
			set.setHeaders(TableModelUtils.getSelectColumnsFromColumnIds(dto.getIds(), columns));
			set.setRows(rows);
			set.setEtag(dto.getEtag());
			return set;
		} finally {
			// Need to close the stream unconditionally.
			object.getObjectContent().close();
		}
	}
	
	@Override
	public SparseChangeSetDto getRowSet(String tableId, long rowVersion) throws IOException {
		TableRowChange dto = getTableRowChange(tableId, rowVersion);
		// Download the file from S3
		S3Object object = s3Client.getObject(dto.getBucket(), dto.getKeyNew());
		try {
			return TableModelUtils.readSparseChangeSetDtoFromGzStream(object.getObjectContent());
		} finally {
			// Need to close the stream unconditionally.
			object.getObjectContent().close();
		}
	}

	@Override
	public void deleteAllRowDataForTable(String tableId) {
		// List key so we can delete them
		List<String> keysToDelete = listAllKeysForTable(tableId);
		// Delete each object from S3
		for (String key : keysToDelete) {
			s3Client.deleteObject(s3Bucket, key);
		}
		// let cascade delete take care of deleting the row changes
		jdbcTemplate.update(SQL_DELETE_ROW_DATA_FOR_TABLE, KeyFactory.stringToKey(tableId));
	}

	@Override
	public void truncateAllRowData() {
		// List key so we can delete them
		List<String> keysToDelete = listAllKeys();
		// Delete each object from S3
		for (String key : keysToDelete) {
			s3Client.deleteObject(s3Bucket, key);
		}
		jdbcTemplate.update(SQL_TRUNCATE_SEQUENCE_TABLE);
	}

	/**
	 * List all of the S3 Keys
	 * 
	 * @return
	 */
	private List<String> listAllKeys() {
		return jdbcTemplate.query(SQL_LIST_ALL_KEYS,
				new RowMapper<String>() {
					@Override
					public String mapRow(ResultSet rs, int rowNum)
							throws SQLException {
						return rs.getString(COL_TABLE_ROW_KEY);
					}
				});
	}

	/**
	 * List all of the S3 Keys for a table
	 * 
	 * @return
	 */
	private List<String> listAllKeysForTable(String tableId) {
		long tableIdLong = KeyFactory.stringToKey(tableId);
		return jdbcTemplate.query(SQL_LIST_ALL_KEYS_FOR_TABLE, new RowMapper<String>() {
			@Override
			public String mapRow(ResultSet rs, int rowNum) throws SQLException {
				return rs.getString(COL_TABLE_ROW_KEY);
			}
		}, tableIdLong, tableIdLong);
	}

	public String getS3Bucket() {
		return s3Bucket;
	}

	/**
	 * IoC
	 * 
	 * @param s3Bucket
	 */
	public void setS3Bucket(String s3Bucket) {
		this.s3Bucket = s3Bucket;
	}

	protected void throwUpdateConflict(Long rowId) {
		throw new ConflictingUpdateException("Row id: " + rowId
				+ " has been changed since last read.  Please get the latest value for this row and then attempt to update it again.");
	}
	
	/**
	 * Check for a row level conflicts in the passed change sets, by scanning each row of each change set and looking
	 * for the intersection with the passed row Ids.
	 * 
	 * @param tableId
	 * @param delta
	 * @param coutToReserver
	 * @throws ConflictingUpdateException when a conflict is found
	 */
	@Override
	public void checkForRowLevelConflict(String tableIdString, SparseChangeSetDto delta) throws IOException {
		// Map each valid row to its version number
		Map<Long, Long> rowIdToRowVersionNumberFromUpdate = TableModelUtils.getDistictValidRowIds(delta.getRows());
		long versionOfDelta = -1;
		for(Long versionNumber: rowIdToRowVersionNumberFromUpdate.values()){
			if(versionNumber == null){
				throw new IllegalArgumentException("Row version number cannot be null");
			}
			versionOfDelta = Math.max(versionOfDelta, versionNumber);
		}
		// If we were given an etag we can use it to determine the version used to create the delta.
		if(delta.getEtag() != null){
			long versionOfEtag = getVersionForEtag(tableIdString, delta.getEtag());
			versionOfDelta = Math.max(versionOfDelta, versionOfEtag);
		}
		final Set<Long> deltaRowIds = rowIdToRowVersionNumberFromUpdate.keySet();
		// Need to check all changes that have been applied since the version of the delta?
		List<TableRowChange> rowChanges = listRowSetsKeysForTableGreaterThanVersion(tableIdString, versionOfDelta);
		// scan all changes greater than this row.
		for (final TableRowChange rowChange : rowChanges) {
			// Scan all recent updates
			SparseChangeSetDto previousChange = this.getRowSet(tableIdString, rowChange.getRowVersion());
			for(SparseRowDto row: previousChange.getRows()){
				if(deltaRowIds.contains(row.getRowId())){
					throwUpdateConflict(row.getRowId());
				}
			}
		}
	}

}
