package org.sagebionetworks.repo.manager.grid.synch;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.sagebionetworks.repo.model.grid.patch.ConValue;

/**
 * Handler for synchronizing changes between a copy (replica) and its source.
 */
public interface SourceHandler extends AutoCloseable {

	/**
	 * Get reader for all rows from the source.
	 * 
	 * @return
	 * @throws IOException
	 */
	RowReader getSourceRowReader() throws IOException;

	/**
	 * Given a RowView provide the key that is used to identify this row in the
	 * source.
	 * 
	 * @param rowView
	 * @return
	 */
	String getRowKey(CopyRow rowView);

	/**
	 * Add a new row to the source.
	 * @param copy
	 */
	void addNewRowToSource(SynchRow copy);

	/**
	 * Get the ColumnModel schema that defines the current columns form the source.
	 * @return
	 */
	List<String> getCurrentSourceSchema();

	
	/**
	 * Add a new column to the source.
	 * @param name
	 */
	void addColumnToSource(String name);

	/**
	 * Applies only the cells that changed in the copy to the source row. This
	 * prevents data loss when external changes occur in the source between reading
	 * and writing. By only updating cells that actually changed in the copy, any
	 * source cells that were modified after we read the row will be preserved.
	 * 
	 * @param rowId        The identifier of the row to update
	 * @param changedCells Map of column names to new values (only cells that
	 *                     changed in the copy)
	 */
	void applyCellChangesFromCopyToSource(String rowId, Map<String, ConValue> changedCells);

	void deleteColumn(String columnName);

}
