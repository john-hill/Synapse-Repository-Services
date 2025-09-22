package org.sagebionetworks.repo.manager.grid.internal.replica.merge;

import org.sagebionetworks.util.PaginationIterator;

public interface GridCsvImportDao {
	
	String TEMP_TABLE_CSV_DATA = "TEMP_CSV_DATA";
	String TEMP_TABLE_GRID_DATA = "TEMP_GRID_DATA";
	
	void streamToCsvTempTable(DataStream dataIterator);
	
	void streamToGridTempTable(DataStream dataIterator);
	
	PaginationIterator<Object[]> getCsvTempTableIterator();
	
	PaginationIterator<Object[]> getGridTempTableIterator();
	
	PaginationIterator<JoinedRow> getJoinedTempTableIterator(ColumnMapping[] csvColumnMapping);
}
