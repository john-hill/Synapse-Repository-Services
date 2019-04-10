package org.sagebionetworks.repo.manager.migration;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.List;

import org.apache.commons.dbcp2.BasicDataSource;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public class TransactionBackfill {

	public static long ONE_MINUTE_MS = 1000L * 60L;

	JdbcTemplate idGenTemplate;
	JdbcTemplate dbTemplate;

	public TransactionBackfill(String[] args) throws SQLException {
		// Id generator connection.
		BasicDataSource idGenSource = new BasicDataSource();
		idGenSource.setDriver(new com.mysql.cj.jdbc.Driver());
		idGenSource.setUrl(args[0]);
		idGenSource.setUsername(args[1]);
		idGenSource.setPassword(args[2]);
		idGenTemplate = new JdbcTemplate(idGenSource);
		long currentMaxId = idGenTemplate.queryForObject("SELECT MAX(ID) FROM TABLE_TRANSACTION_ID", Long.class, null);
		System.out.println("SELECT MAX(ID) FROM TABLE_TRANSACTION_ID: " + currentMaxId);

		// main db connection
		BasicDataSource dbSource = new BasicDataSource();
		dbSource.setDriver(new com.mysql.cj.jdbc.Driver());
		dbSource.setUrl(args[3]);
		dbSource.setUsername(args[4]);
		dbSource.setPassword(args[5]);
		dbTemplate = new JdbcTemplate(dbSource);
		long transactionCount = dbTemplate.queryForObject("SELECT COUNT(*) FROM TABLE_ROW_CHANGE WHERE TRX_ID IS NULL",
				Long.class, null);
		System.out.println("SELECT COUNT(*) FROM TABLE_ROW_CHANGE WHERE TRX_ID IS NULL: " + transactionCount);
	}

	/**
	 * Start the backfill of all changes that are missing a transaction.
	 * 
	 */
	public void startBackfill() {
		Iterator<TableRowChange> changesMissingTransactions = new MissingTransactionIterator();
		TableRowChange lastChange = null;
		while (changesMissingTransactions.hasNext()) {
			TableRowChange next = changesMissingTransactions.next();
			lastChange = findOrCreateTransaction(next, lastChange);
			System.out.println(lastChange);
		}
	}

	/**
	 * Find an existing transaction to match the provided change. If a match cannot
	 * be found create a new transaction.
	 * 
	 * @param rowChange
	 */
	public TableRowChange findOrCreateTransaction(TableRowChange rowChange, TableRowChange lastChange) {
		Long transactionId = null;
		if(lastChange == null) {
			// Attempt to match the previous change's transaction to this change.
			transactionId = getPreviousTransactionIdIfMatches(rowChange);
		}else if(changesAreFromSameTransaction(rowChange, lastChange)) {
			transactionId = lastChange.transactionId;
		}
		if (transactionId == null) {
			// no match found so start a new transaction for this change
			transactionId = startTransactionForChange(rowChange);
		}
		// Assign the transaction ID to this change.
		setChangeTransactionId(rowChange, transactionId);
		rowChange.transactionId = transactionId;
		return rowChange;
	}
	
	/**
	 * Two changes are from the same transaction, if they they have the same tableId,
	 * they are created by the same users, and they were created within one minute
	 * of each other.
	 * @param rowChange
	 * @param lastChange
	 * @return
	 */
	public static boolean changesAreFromSameTransaction(TableRowChange rowChange, TableRowChange lastChange) {
		if(lastChange == null) {
			return false;
		}
		if(rowChange.tableId != lastChange.tableId) {
			return false;
		}
		if(rowChange.createdBy != lastChange.createdBy) {
			return false;
		}
		return lastChange.createdOn > rowChange.createdOn - ONE_MINUTE_MS;
	}

	/**
	 * Start a new transaction for the given table
	 * 
	 * @param rowChange
	 * @return
	 */
	public long startTransactionForChange(TableRowChange rowChange) {
		// Generate a new ID
		idGenTemplate.update("INSERT INTO TABLE_TRANSACTION_ID (CREATED_ON) VALUES (?)", System.currentTimeMillis());
		long trxId = idGenTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		// Apply the id
		dbTemplate.update("INSERT INTO TABLE_TRANSACTION " + "(TRX_ID,TABLE_ID,STARTED_BY,STARTED_ON) VALUES (?,?,?,?)",
				trxId, rowChange.tableId, rowChange.createdBy, rowChange.createdOn);
		return trxId;
	}

	/**
	 * Lookup the previous transaction for this table.
	 * 
	 * @param rowChange
	 * @return
	 */
	public Long getPreviousTransactionIdIfMatches(TableRowChange rowChange) {
		try {
			// Find the previous row version
			Long previousRowVersion = dbTemplate.queryForObject(
					"SELECT MAX(ROW_VERSION) FROM TABLE_ROW_CHANGE WHERE TABLE_ID = ? AND ROW_VERSION < ?", Long.class,
					rowChange.tableId, rowChange.rowVersion);
			if (previousRowVersion == null) {
				return null;
			}
			// Lookup the transaction for the previous change if it matches this user and is
			// within an hour
			return dbTemplate.queryForObject(
					"SELECT T.TRX_ID FROM TABLE_TRANSACTION T"
							+ " JOIN TABLE_ROW_CHANGE C ON (T.TABLE_ID = C.TABLE_ID AND T.TRX_ID = C.TRX_ID)"
							+ " WHERE C.TABLE_ID = ? AND C.ROW_VERSION = ? AND C.CREATED_BY = ? AND C.CREATED_ON > ?",
					Long.class, rowChange.tableId, previousRowVersion, rowChange.createdBy,
					rowChange.createdOn - ONE_MINUTE_MS);
		} catch (EmptyResultDataAccessException e) {
			return null;
		}
	}

	/**
	 * Set the transaction ID for the given table change.
	 * 
	 * @param rowChange
	 * @param transactionId
	 */
	public void setChangeTransactionId(TableRowChange rowChange, long transactionId) {
		dbTemplate.update("UPDATE TABLE_ROW_CHANGE SET TRX_ID = ? WHERE TABLE_ID = ? AND ROW_VERSION = ?",
				transactionId, rowChange.tableId, rowChange.rowVersion);
	}

	/**
	 * Iterate over all TABLE_ROW_CHANGES that have a null transaction id.
	 *
	 */
	public class MissingTransactionIterator implements Iterator<TableRowChange> {
		final long limit = 10000;
		long offset = 0;
		Iterator<TableRowChange> lastPage = null;

		@Override
		public boolean hasNext() {
			if (lastPage != null && lastPage.hasNext()) {
				return true;
			}
			List<TableRowChange> page = queryNextPage();
			if (page.isEmpty()) {
				return false;
			} else {
				lastPage = page.iterator();
				return hasNext();
			}
		}

		@Override
		public TableRowChange next() {
			return lastPage.next();
		}

		private List<TableRowChange> queryNextPage() {
			List<TableRowChange> page = dbTemplate.query("SELECT TABLE_ID, ROW_VERSION, CREATED_BY, CREATED_ON"
					+ " FROM TABLE_ROW_CHANGE WHERE TRX_ID IS NULL"
					+ " ORDER BY TABLE_ID, ROW_VERSION LIMIT ? OFFSET ?;", new RowMapper<TableRowChange>() {

						@Override
						public TableRowChange mapRow(ResultSet rs, int rowNum) throws SQLException {
							TableRowChange change = new TableRowChange();
							change.tableId = rs.getLong("TABLE_ID");
							change.rowVersion = rs.getLong("ROW_VERSION");
							change.createdBy = rs.getLong("CREATED_BY");
							change.createdOn = rs.getLong("CREATED_ON");
							return change;
						}
					}, limit, offset);
			offset += limit;
			return page;
		}

	}

	/**
	 * A single row change.
	 */
	public static class TableRowChange {
		long tableId;
		long rowVersion;
		long createdBy;
		long createdOn;
		long transactionId;
		@Override
		public String toString() {
			return "TableRowChange [tableId=" + tableId + ", rowVersion=" + rowVersion + ", createdBy=" + createdBy
					+ ", createdOn=" + createdOn + ", transactionId=" + transactionId + "]";
		}

	}

	public static void main(String[] args) throws SQLException {
		TransactionBackfill backfill = new TransactionBackfill(args);
		backfill.startBackfill();
	}
}
