package org.sagebionetworks.repo.manager.table;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import org.sagebionetworks.common.util.progress.ProgressCallback;
import org.sagebionetworks.repo.model.DatastoreException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.table.ColumnChangeDetails;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.Row;
import org.sagebionetworks.repo.model.table.RowReference;
import org.sagebionetworks.repo.model.table.RowReferenceSet;
import org.sagebionetworks.repo.model.table.RowSelection;
import org.sagebionetworks.repo.model.table.RowSet;
import org.sagebionetworks.repo.model.table.TableRowChange;
import org.sagebionetworks.repo.model.table.TableUpdateRequest;
import org.sagebionetworks.repo.model.table.TableUpdateResponse;
import org.sagebionetworks.repo.web.NotFoundException;
import org.sagebionetworks.repo.web.TemporarilyUnavailableException;
import org.sagebionetworks.table.model.SparseChangeSet;

/**
 * Abstraction for Table Row management.
 * 
 * @author jmhill
 * 
 */
public interface TableEntityManager extends AppendableTableManager {


	/**
	 * Delete a set of rows from a table.
	 * 
	 */
	public RowReferenceSet deleteRows(UserInfo user, String tableId, RowSelection rowsToDelete)
			throws DatastoreException, NotFoundException, IOException;

	/**
	 * Delete all rows from a table.
	 * 
	 * @param models
	 */
	public void deleteAllRows(String id);

	/**
	 * List the changes that have been applied to a table.
	 * 
	 * @param tableId
	 * @return
	 */
	public List<TableRowChange> listRowSetsKeysForTable(String tableId);
	
	/**
	 * Get the a SparseChangeSet for a given TableRowChange.
	 * 
	 * @param change
	 * @return
	 * @throws IOException 
	 * @throws NotFoundException 
	 */
	public SparseChangeSet getSparseChangeSet(TableRowChange change) throws NotFoundException, IOException;
	
	/**
	 * Get the schema change for a given version.
	 * 
	 * @param tableId
	 * @param versionNumber
	 * @return
	 * @throws IOException
	 */
	public List<ColumnChangeDetails> getSchemaChangeForVersion(String tableId, long versionNumber) throws IOException;

	/**
	 * Get the last table row change
	 * 
	 * @param tableId
	 * @return
	 * @throws IOException
	 * @throws NotFoundException
	 */
	public TableRowChange getLastTableRowChange(String tableId) throws IOException, NotFoundException;

	/**
	 * Get the highest possible row id in this table
	 * 
	 * @param tableId
	 * @return the highest possible row id
	 * @throws IOException
	 * @throws NotFoundException
	 */
	public long getMaxRowId(String tableId) throws IOException, NotFoundException;;

	/**
	 * Get the values for a specific row reference and column
	 * 
	 * @param userInfo
	 * @param refSet
	 * @param model
	 * @return
	 * @throws NotFoundException
	 * @throws IOException
	 */
	public Row getCellValue(UserInfo userInfo, String tableId, RowReference rowRef, ColumnModel model) throws IOException,
			NotFoundException;

	/**
	 * Get the values for a specific row reference set and columns
	 * 
	 * @param userInfo
	 * @param refSet
	 * @param model
	 * @return
	 * @throws NotFoundException
	 * @throws IOException
	 */
	public RowSet getCellValues(UserInfo userInfo, String tableId, List<RowReference> rows, List<ColumnModel> columns)
			throws IOException, NotFoundException;
	
	/**
	 * Given a set of FileHandleIds and a talbeId, get the sub-set of
	 * FileHandleIds that are actually associated with the table.
	 * @param objectId
	 * @throws TemporarilyUnavailableException if this query cannot be run at this time.
	 */
	public Set<Long> getFileHandleIdsAssociatedWithTable(String tableId, Set<Long> toTest) throws TemporarilyUnavailableException;
	
	/**
	 * Given a set of FileHandleIds and a talbeId, get the sub-set of
	 * FileHandleIds that are actually associated with the table.
	 * @param objectId
	 */
	public Set<String> getFileHandleIdsAssociatedWithTable(String tableId, List<String> toTest) throws TemporarilyUnavailableException;

	/**
	 * Set the schema of the table.
	 * @param userInfo
	 * @param columnIds
	 * @param id
	 */
	public void setTableSchema(UserInfo userInfo, List<String> columnIds,
			String id);

	/**
	 * Delete the table.
	 * @param deletedId
	 */
	public void deleteTable(String deletedId);

	/**
	 * Is a temporary table needed to validate the given table update request.
	 * @param change
	 * @return
	 */
	public boolean isTemporaryTableNeededToValidate(TableUpdateRequest change);

	/**
	 * Validate a single update request.
	 * @param callback
	 * @param userInfo
	 * @param change
	 * @param indexManager The index manager is only provided if a temporary table was created 
	 * for the purpose of validation.
	 */
	public void validateUpdateRequest(ProgressCallback<Void> callback,
			UserInfo userInfo, TableUpdateRequest change,
			TableIndexManager indexManager);

	/**
	 * Update the table with the given request.
	 * @param callback
	 * @param userInfo
	 * @param change
	 * @return
	 */
	public TableUpdateResponse updateTable(ProgressCallback<Void> callback,
			UserInfo userInfo, TableUpdateRequest change);

	/**
	 * Get the schema for the table.
	 * @param user
	 * @param id
	 * @return
	 */
	public List<String> getTableSchema(String id);

}
