package org.sagebionetworks.repo.manager.migration;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.sagebionetworks.repo.model.dao.table.ColumnModelDAO;
import org.sagebionetworks.repo.model.dao.table.RowHandler;
import org.sagebionetworks.repo.model.dao.table.TableFileAssociationDao;
import org.sagebionetworks.repo.model.dao.table.TableRowTruthDAO;
import org.sagebionetworks.repo.model.dbo.dao.table.TableRowChangeUtils;
import org.sagebionetworks.repo.model.dbo.persistence.table.DBOTableRowChange;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.Row;
import org.sagebionetworks.repo.model.table.TableRowChange;
import org.sagebionetworks.repo.transactions.NewWriteTransaction;
import org.sagebionetworks.table.cluster.utils.TableModelUtils;
import org.springframework.beans.factory.annotation.Autowired;

import com.amazonaws.services.sqs.AmazonSQSClient;

public class TableFileMigrationImpl implements TableFileMigration {

	private static final Logger log = LogManager
			.getLogger(TableFileMigrationImpl.class);

	@Autowired
	TableRowTruthDAO tableRowTruthDAO;

	@Autowired
	ColumnModelDAO columnModelDao;

	@Autowired
	TableFileAssociationDao tableFileAssociationDao;

	@Autowired
	AmazonSQSClient awsSQSClient;

	/**
	 * This method can fail for tables that are in a bad state. A new
	 * transaction is used to prevent exceptions that could be thrown during an
	 * attempt to roll-back the migration transaction.
	 * 
	 * @throws IOException
	 */
	@NewWriteTransaction
	@Override
	public void attemptTableFileMigration(final DBOTableRowChange change)
			throws IOException {
		String tableId = change.getTableId().toString();
		TableRowChange tableRowChange = TableRowChangeUtils
				.ceateDTOFromDBO(change);
		List<String> columnIds = new LinkedList<String>();
		for (Long longId : tableRowChange.getIds()) {
			columnIds.add(longId.toString());
		}
		List<ColumnModel> columns = columnModelDao.getColumnModel(columnIds,
				true);
		final List<Row> rows = new LinkedList<Row>();
		tableRowTruthDAO.scanChange(new RowHandler() {

			@Override
			public void nextRow(Row row) {
				rows.add(row);
			}
		}, tableRowChange);
		// We can now extract the file handle ids
		Set<String> fileHandleIds = TableModelUtils.getFileHandleIdsInRowSet(
				columns, rows);
		// bind the files to the table
		tableFileAssociationDao
				.bindFileHandleIdsToTable(tableId, fileHandleIds);
	}

}
