package org.sagebionetworks.repo.manager.grid.internal.replica.merge;

import java.util.Iterator;

public interface DataStream extends Iterator<Object[]> {
	
	/**
	 * @return An array of column mappings that are first ordered by the upsert key columns
	 */
	ColumnMapping[] getColumnMapping();
}
