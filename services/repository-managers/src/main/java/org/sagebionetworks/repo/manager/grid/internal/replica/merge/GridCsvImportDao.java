package org.sagebionetworks.repo.manager.grid.internal.replica.merge;

import java.util.Iterator;

public interface GridCsvImportDao {
	
	String TEMP_TABLE_CSV_DATA = "TEMP_CSV_DATA";
	String TEMP_TABLE_GRID_DATA = "TEMP_GRID_DATA";
	
	void streamToCsvTempTable(DataStream dataIterator, ColumnMapping[] columnMapping);
	
	void streamToGridTempTable(DataStream dataIterator, ColumnMapping[] columnMapping);
	
	Iterator<Object[]> getCsvTempTableIterator();
	
	Iterator<Object[]> getGridTempTableIterator();
	
	Iterator<JoinedRow> getJoinedTempTableIterator(ColumnMapping[] columnMapping);
}
