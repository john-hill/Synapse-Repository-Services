package org.sagebionetworks.repo.manager.grid.internal.replica.merge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.StringReader;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.Column;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.GridHeader;
import org.sagebionetworks.repo.model.table.ColumnType;

import au.com.bytecode.opencsv.CSVReader;

public class CsvDataStreamTest {

	private CSVReader csvReader;
	private GridHeader gridHeader;
	private List<String> upsertKey;
	
	private CsvDataStream dataStream;
	
	@BeforeEach
	public void beforeEach() {
		
		gridHeader = new GridHeader().setOrderedColumns(List.of(
			new Column().setName("a"),
			new Column().setName("b"),
			new Column().setName("c")
		));
		
		upsertKey = List.of("a");
		
		csvReader = new CSVReader(new StringReader(
			"a,b,c,d" 			+ System.lineSeparator() +
			"0,1,data0,extra0"	+ System.lineSeparator() +
			"1,2,data1,etxra1" 	+ System.lineSeparator() +
			"2,3,data2,extra2" 	+ System.lineSeparator() +
			"3,4,data3,extra3" 	+ System.lineSeparator() +
			"4,5,data4,extra4" 	+ System.lineSeparator()
		));
	}
	
	@Test
	public void testStream() {
		dataStream = new CsvDataStream(csvReader, gridHeader, upsertKey);
		
		assertArrayEquals(new ColumnMapping[] {
			new ColumnMapping("a", ColumnType.INTEGER, 0, true),
			new ColumnMapping("b", null, 1, false),
			new ColumnMapping("c", null, 2, false)
		}, dataStream.getColumnMapping());
		
		long rowId = 0;
		
		while(dataStream.hasNext()) {
			Object[] row = dataStream.next();
			
			assertArrayEquals(new Object[] {
				rowId,						// a
				String.valueOf(rowId + 1),	// b
				"data"+ rowId				// c
			}, row);
			
			rowId++;
		}
	}
	
	@Test
	public void testStreamWithOutOfOrderUpsertKey() {
		upsertKey = List.of("b", "a");
		
		dataStream = new CsvDataStream(csvReader, gridHeader, upsertKey);
		
		assertArrayEquals(new ColumnMapping[] {
			new ColumnMapping("b", ColumnType.INTEGER, 1, true),
			new ColumnMapping("a", ColumnType.INTEGER, 0, true),
			new ColumnMapping("c", null, 2, false)
		}, dataStream.getColumnMapping());
		
		long rowId = 0;
		
		while(dataStream.hasNext()) {
			Object[] row = dataStream.next();
			
			assertArrayEquals(new Object[] {
				rowId + 1,					// b
				rowId,						// a
				"data"+ rowId	// c
			}, row);
			
			rowId++;
		}
	}
	
	@Test
	public void testStreamWithOutOfOrderGridColumns() {
		gridHeader = new GridHeader().setOrderedColumns(List.of(
			new Column().setName("c"),
			new Column().setName("a"),
			new Column().setName("b")
		));
		
		upsertKey = List.of("a");
		
		dataStream = new CsvDataStream(csvReader, gridHeader, upsertKey);
		
		assertArrayEquals(new ColumnMapping[] {
			new ColumnMapping("a", ColumnType.INTEGER, 0, true),
			new ColumnMapping("b", null, 1, false),
			new ColumnMapping("c", null, 2, false)
		}, dataStream.getColumnMapping());
		
		long rowId = 0;
		
		while(dataStream.hasNext()) {
			Object[] row = dataStream.next();
			
			assertArrayEquals(new Object[] {
				rowId,						// a
				String.valueOf(rowId + 1),	// b
				"data" + rowId				// c
			}, row);
			
			rowId++;
		}
	}
	
	@Test
	public void testStreamWithOutOfOrderCsvColumns() {
		gridHeader = new GridHeader().setOrderedColumns(List.of(
			new Column().setName("a"),
			new Column().setName("b"),
			new Column().setName("c")
		));
		
		csvReader = new CSVReader(new StringReader(
			"c,b,a,d" 			+ System.lineSeparator() +
			"data0,1,0,extra0"	+ System.lineSeparator() +
			"data1,2,1,etxra1" 	+ System.lineSeparator() +
			"data2,3,2,extra2" 	+ System.lineSeparator() +
			"data3,4,3,extra3" 	+ System.lineSeparator() +
			"data4,5,4,extra4" 	+ System.lineSeparator()
		));
		
		upsertKey = List.of("a");
		
		dataStream = new CsvDataStream(csvReader, gridHeader, upsertKey);
		
		assertArrayEquals(new ColumnMapping[] {
			new ColumnMapping("a", ColumnType.INTEGER, 2, true),
			new ColumnMapping("c", null, 0, false),
			new ColumnMapping("b", null, 1, false)
		}, dataStream.getColumnMapping());
		
		long rowId = 0;
		
		while(dataStream.hasNext()) {
			Object[] row = dataStream.next();
			
			assertArrayEquals(new Object[] {
				rowId,						// a
				"data"+ rowId, 				// c
				String.valueOf(rowId + 1)	// b
			}, row);
			
			rowId++;
		}
	}
	
	@Test
	public void testStreamWithOutOfOrderGridAndCsvColumns() {
		gridHeader = new GridHeader().setOrderedColumns(List.of(
			new Column().setName("c"),
			new Column().setName("a"),
			new Column().setName("b")
		));
		
		csvReader = new CSVReader(new StringReader(
			"c,b,a,d" 			+ System.lineSeparator() +
			"data0,1,0,extra0"	+ System.lineSeparator() +
			"data1,2,1,etxra1" 	+ System.lineSeparator() +
			"data2,3,2,extra2" 	+ System.lineSeparator() +
			"data3,4,3,extra3" 	+ System.lineSeparator() +
			"data4,5,4,extra4" 	+ System.lineSeparator()
		));
		
		upsertKey = List.of("a");
		
		dataStream = new CsvDataStream(csvReader, gridHeader, upsertKey);
		
		assertArrayEquals(new ColumnMapping[] {
			new ColumnMapping("a", ColumnType.INTEGER, 2, true),
			new ColumnMapping("c", null, 0, false),
			new ColumnMapping("b", null, 1, false)
		}, dataStream.getColumnMapping());
		
		long rowId = 0;
		
		while(dataStream.hasNext()) {
			Object[] row = dataStream.next();
			
			assertArrayEquals(new Object[] {
				rowId,						// a
				"data"+ rowId, 				// c
				String.valueOf(rowId + 1)	// b
			}, row);
			
			rowId++;
		}
	}
	
	@Test
	public void testStreamWithMissingColumn() {
		gridHeader = new GridHeader().setOrderedColumns(List.of(
			new Column().setName("a"),
			new Column().setName("b"),
			new Column().setName("c")
		));
		
		upsertKey = List.of("a");
		
		csvReader = new CSVReader(new StringReader(
			"a,b,d" 		+ System.lineSeparator() +
			"0,1,extra0"	+ System.lineSeparator() +
			"1,2,etxra1" 	+ System.lineSeparator() +
			"2,3,extra2" 	+ System.lineSeparator() +
			"3,4,extra3" 	+ System.lineSeparator() +
			"4,5,extra4" 	+ System.lineSeparator()
		));
		
		dataStream = new CsvDataStream(csvReader, gridHeader, upsertKey);
		
		assertArrayEquals(new ColumnMapping[] {
			new ColumnMapping("a", ColumnType.INTEGER, 0, true),
			new ColumnMapping("b", null, 1, false)
		}, dataStream.getColumnMapping());
		
		long rowId = 0;
		
		while(dataStream.hasNext()) {
			Object[] row = dataStream.next();
			
			assertArrayEquals(new Object[] {
				rowId,						// a
				String.valueOf(rowId + 1) 	// b
			}, row);
			
			rowId++;
		}
	}
	
	@Test
	public void testStreamWithMissingUpsertKeyColumn() {
		gridHeader = new GridHeader().setOrderedColumns(List.of(
			new Column().setName("a"),
			new Column().setName("b"),
			new Column().setName("c")
		));
		
		upsertKey = List.of("a");
		
		csvReader = new CSVReader(new StringReader(
			"b,d" 		+ System.lineSeparator() +
			"1,extra0"	+ System.lineSeparator() +
			"2,etxra1" 	+ System.lineSeparator() +
			"3,extra2" 	+ System.lineSeparator() +
			"4,extra3" 	+ System.lineSeparator() +
			"5,extra4" 	+ System.lineSeparator()
		));
		
		assertEquals("The upsert key column \"a\" does not exist in the CSV file.", assertThrows(IllegalArgumentException.class, () -> {
			new CsvDataStream(csvReader, gridHeader, upsertKey);
		}).getMessage());
		
	}
	
	@Test
	public void testStreamWithEmptyUpsertKeyColumn() {
		gridHeader = new GridHeader().setOrderedColumns(List.of(
			new Column().setName("a"),
			new Column().setName("b"),
			new Column().setName("c")
		));
		
		upsertKey = List.of("a");
		
		csvReader = new CSVReader(new StringReader(
			"a,b,d" 		+ System.lineSeparator() +
			",1,extra0"	+ System.lineSeparator() +
			"1,2,etxra1" 	+ System.lineSeparator() +
			"2,3,extra2" 	+ System.lineSeparator() +
			"3,4,extra3" 	+ System.lineSeparator() +
			"4,5,extra4" 	+ System.lineSeparator()
		));
		
		assertEquals("The upsert key cannot have null or empty values.", assertThrows(IllegalArgumentException.class, () -> {
			new CsvDataStream(csvReader, gridHeader, upsertKey);
		}).getMessage());
		
		csvReader = new CSVReader(new StringReader(
			"a,b,d" 		+ System.lineSeparator() +
			"0,1,extra0"	+ System.lineSeparator() +
			"1,2,etxra1" 	+ System.lineSeparator() +
			",3,extra2" 	+ System.lineSeparator() +
			"3,4,extra3" 	+ System.lineSeparator() +
			"4,5,extra4" 	+ System.lineSeparator()
		));
		
		dataStream = new CsvDataStream(csvReader, gridHeader, upsertKey);
		
		assertEquals("The upsert key cannot have null or empty values.", assertThrows(IllegalArgumentException.class, () -> {
			while(dataStream.hasNext()) {
				dataStream.next();
			}
		}).getMessage());
		
	}
	
	@Test
	public void testStreamWithEmptyCsv() {
		gridHeader = new GridHeader().setOrderedColumns(List.of(
			new Column().setName("a"),
			new Column().setName("b"),
			new Column().setName("c")
		));
		
		upsertKey = List.of("a");
		
		csvReader = new CSVReader(new StringReader(
			"a,b,d"
		));
		
		assertEquals("The CSV file cannot be empty.", assertThrows(IllegalArgumentException.class, () -> {
			new CsvDataStream(csvReader, gridHeader, upsertKey);
		}).getMessage());
		
		csvReader = new CSVReader(new StringReader(""));
		
		assertEquals("The CSV file cannot be empty.", assertThrows(IllegalArgumentException.class, () -> {
			new CsvDataStream(csvReader, gridHeader, upsertKey);
		}).getMessage());
		
	}

}
