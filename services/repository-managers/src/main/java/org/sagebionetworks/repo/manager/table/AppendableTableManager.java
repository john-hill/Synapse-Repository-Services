package org.sagebionetworks.repo.manager.table;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;

import org.sagebionetworks.common.util.progress.ProgressCallback;
import org.sagebionetworks.repo.model.DatastoreException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.PartialRowSet;
import org.sagebionetworks.repo.model.table.RowReferenceSet;
import org.sagebionetworks.repo.model.table.RowSet;
import org.sagebionetworks.repo.model.table.SparseRowDto;
import org.sagebionetworks.repo.web.NotFoundException;

/**
 * Abstraction for a table manager that can append rows to a table.
 *
 */
public interface AppendableTableManager {

	/**
	 * Append a set of rows to a table.
	 * 
	 * @param user
	 * @param delta
	 * @return
	 * @throws NotFoundException
	 * @throws DatastoreException
	 * @throws IOException
	 */
	public RowReferenceSet appendRows(UserInfo user, String tableId, RowSet delta, ProgressCallback<Long> progressCallback)
			throws DatastoreException, NotFoundException, IOException;

	/**
	 * Append or update a set of partial rows to a table.
	 * 
	 * @param user
	 * @param tableId
	 * @param models
	 * @param rowsToAppendOrUpdate
	 * @return
	 * @throws IOException
	 * @throws NotFoundException
	 * @throws DatastoreException
	 */
	public RowReferenceSet appendPartialRows(UserInfo user, String tableId,
			PartialRowSet rowsToAppendOrUpdateOrDelete, ProgressCallback<Long> progressCallback) throws DatastoreException, NotFoundException, IOException;
	
	/**
	 * Append all rows from the provided iterator into the a table. This method
	 * will batch rows into optimum sized RowSets.
	 * 
	 * Note: This method will only keep one batch of rows in memory at a time so
	 * it should be suitable for appending very large change sets to a table.
	 * 
	 * @param user The user appending the rows
	 * @param tableId The ID of the table entity to append the rows too.
	 * @param models The schema of the rows being appended.
	 * @param rowStream The stream of rows to append to the table.
	 * @param results
	 *            This parameter is optional. When provide, it will be populated
	 *            with a RowReference for each row appended to the table. This
	 *            parameter should be null for large change sets to minimize
	 *            memory usage.
	 * @param The callback will be called for each batch of rows appended to the table.  Can be null.
	 * @return
	 * @throws DatastoreException
	 * @throws NotFoundException
	 * @throws IOException
	 */
	String appendRowsAsStream(UserInfo user, String tableId, List<ColumnModel> columns, Iterator<SparseRowDto> rowStream, String etag,
			RowReferenceSet results, ProgressCallback<Void> progressCallback) throws DatastoreException, NotFoundException, IOException;
}
