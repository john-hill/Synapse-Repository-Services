package org.sagebionetworks.repo.manager.grid.internal.replica.merge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.json.JSONArray;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.grid.db.GridIndexManager;
import org.sagebionetworks.repo.manager.grid.PatchRowHandler;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.GridHeader;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.RowView;
import org.sagebionetworks.repo.manager.grid.internal.replica.view.GridReplicaViewManager;
import org.sagebionetworks.repo.model.grid.GridUtils;
import org.sagebionetworks.repo.model.grid.patch.compact.PatchCompactSerializable;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.repo.model.table.Row;
import org.sagebionetworks.table.cluster.utils.CSVUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import au.com.bytecode.opencsv.CSVReader;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = { "classpath:test-context.xml" })
public class GridCsvImportDaoImplTest {

	@Autowired
	private GridReplicaViewManager gridViewManager;
	@Autowired
	private GridIndexManager gridIndexManger;
	@Autowired
	private GridCsvImportDao importDao;
	
	@BeforeEach
	public void before() {
		gridIndexManger.truncateAll();
	}
	
	@AfterEach
	public void after() {
		gridIndexManger.truncateAll();
	}
	
	@Test
	public void testImportAndJoin() throws IOException {
		// First create some data in a grid
		String sessionId = GridUtils.gridSessionIdAsString(123L);
		Long replicaId = 987L;
		int gridRowCount = 500;
		int csvRowCount = 750;
		
		List<ColumnModel> schema = List.of(
			new ColumnModel().setName("a").setColumnType(ColumnType.INTEGER),
			new ColumnModel().setName("b").setColumnType(ColumnType.INTEGER),
			new ColumnModel().setName("c").setColumnType(ColumnType.STRING),
			new ColumnModel().setName("d").setColumnType(ColumnType.BOOLEAN)
		);
		
		// Note that the upsert key has a different order than the schema
		List<String> upsertKey = List.of("b", "a");		
		
		List<Row> rows = new ArrayList<>(gridRowCount);
		
		for (int i = 0; i < gridRowCount; i++) {
			rows.add(new Row().setValues(List.of(
				String.valueOf(i%10), 			// a
				String.valueOf(i), 				// b
				"foo" + i, 						// c	
				String.valueOf(i%2 == 0) 		// d
			)));
		}

		long start = System.currentTimeMillis();
		
		writeRowsAsPatches(rows, sessionId, replicaId, schema);
		
		System.out.println("Grid storage took: " + (System.currentTimeMillis() - start) + "ms");
		
		GridHeader gridHeader = gridViewManager.readHeader(sessionId, replicaId).orElseThrow();
		
		start = System.currentTimeMillis();
		
		Iterator<RowView> rowView = gridViewManager.getQueryIterator(gridHeader, Collections.emptyList());
		
		DataStream gridDataStream = new GridDataStream(rowView, gridHeader, upsertKey);
		
		importDao.streamToGridTempTable(gridDataStream);
		
		System.out.println("Grid import took: " + (System.currentTimeMillis() - start) + "ms");
		
		Iterator<Object[]> it = importDao.getGridTempTableIterator();
		
		long rowId = 0;
		Long arrId = 29L;
		Long rowVecId = 22L;
		
		while (it.hasNext()) {
			Object[] row = it.next();
			// This is the extra column with information from the grid (logical ids)
			String extraData = "[[" + replicaId + "," + arrId + "],[" + replicaId + "," + rowVecId + "]]";
			// Note that the data has been reordered by the upsert key (b,a) and only contains the upsert keys plus the extra data column
			assertArrayEquals(new Object[] { rowId, rowId % 10, extraData }, row);
			rowId++;
			arrId+=9;
			rowVecId+=9;
		}
		
		// Now import the CSV data (contains a subset of the grid data, with additional columns and different order)
		StringBuilder csv = new StringBuilder();
		
		csv.append("b,a,d,c,e").append(System.lineSeparator());
		
		for (int i=0; i < csvRowCount; i++) {
			csv
				.append(i).append(",") 						// b
				.append(i%5).append(",") 					// a, note that the grid uses mod 10, so we do not match half the upserts
				.append(false).append(",")					// d
				.append("bar" + i).append(",")				// c
				.append("extra" + i)						// e, this is not in the grid
				.append(System.lineSeparator());
		}
		
		List<ColumnModel> csvSchema = List.of(
			new ColumnModel().setName("b").setColumnType(ColumnType.INTEGER),
			new ColumnModel().setName("a").setColumnType(ColumnType.INTEGER),
			new ColumnModel().setName("d").setColumnType(ColumnType.BOOLEAN),
			new ColumnModel().setName("c").setColumnType(ColumnType.STRING),
			new ColumnModel().setName("e").setColumnType(ColumnType.STRING)
		);
		
		start = System.currentTimeMillis();
		
		CSVReader reader = CSVUtils.createCSVReader(new StringReader(csv.toString()), null, null);
		CsvDataStream csvDataStream = new CsvDataStream(reader, csvSchema, gridHeader, upsertKey);
		
		try (reader) {
			importDao.streamToCsvTempTable(csvDataStream);
		}

		System.out.println("CSV import took: " + (System.currentTimeMillis() - start) + "ms");
		
		it = importDao.getCsvTempTableIterator();
		rowId = 0;
		
		while (it.hasNext()) {
			String expectedCsvJson = "[false,\"bar" + rowId + "\"]";
			
			assertArrayEquals(
				// Note that column e is omitted as not present in the grid and the columns that are not
				// the upsert key are stored in a JSON array
				new Object[] { rowId, rowId % 5, expectedCsvJson },
				it.next()
			);
			
			rowId++;
		}
	
		Iterator<JoinedRow> joinIt = importDao.getJoinedTempTableIterator(csvDataStream.getColumnMapping());
		
		rowId = 0;
		arrId = 29L;
		rowVecId = 22L;
		int notMatchedCount = 0;
		
		while (joinIt.hasNext()) {
			
			Object[] expectedCsvData = new Object[] { rowId, rowId % 5, false , "bar" + rowId };
			String[] expectedGridArray = null;
			
			if (rowId % 10 < 5 && rowId < gridRowCount) {
				expectedGridArray = new String[] {
					"[" + replicaId + "," + arrId + "]",
					"[" + replicaId + "," + rowVecId + "]"
				};
			} else {
				notMatchedCount++;
			}
			
			JoinedRow next = joinIt.next();
			
			assertArrayEquals(expectedCsvData, next.getCsvData());
			
			if (expectedGridArray == null) {
				assertNull(next.getGridData());
			} else {
				assertEquals(2, next.getGridData().length);
				// The logical timestamps are automatically converted to JSONArray (which unfortunately does not implement hashcode/equals)
				assertArrayEquals(expectedGridArray, new String[] {
					next.getGridData()[0].toString(),
					next.getGridData()[1].toString()
				});
			}
			
			rowId++;
			arrId+=9;
			rowVecId+=9;
		}
		
		// The CSV has only 50% of the rows in commong with the grid
		assertEquals(gridRowCount/2 + (csvRowCount - gridRowCount), notMatchedCount);
	}
	
	void writeRowsAsPatches(List<Row> rows, String sessionId, Long replicaId, List<ColumnModel> schema) throws IOException {
		
		PatchRowHandler patchRowHandler = new PatchRowHandler((s, pid, body) -> {
			gridIndexManger.applyPatch(sessionId, pid.getReplicaId(), PatchCompactSerializable.deserialize(new JSONArray(body)));
			return true;
		}, sessionId, replicaId, schema, 100L);
		
		try (patchRowHandler) {
			rows.stream().forEach(r -> {
				patchRowHandler.nextRow(r);
			});
		}
	}
		

}
