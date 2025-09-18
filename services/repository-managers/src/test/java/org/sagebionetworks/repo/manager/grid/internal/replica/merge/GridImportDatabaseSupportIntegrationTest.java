package org.sagebionetworks.repo.manager.grid.internal.replica.merge;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.json.JSONArray;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.grid.db.GridIndexManager;
import org.sagebionetworks.grid.db.GridTransaction;
import org.sagebionetworks.repo.manager.grid.PatchRowHandler;
import org.sagebionetworks.repo.manager.grid.internal.replica.merge.GridImportDatabaseSupport.ColumnMapping;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.Column;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.GridHeader;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.RowView;
import org.sagebionetworks.repo.manager.grid.internal.replica.view.GridReplicaViewManager;
import org.sagebionetworks.repo.model.dbo.dao.table.TableModelTestUtils;
import org.sagebionetworks.repo.model.grid.GridUtils;
import org.sagebionetworks.repo.model.grid.patch.Patch;
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
public class GridImportDatabaseSupportIntegrationTest {
	
	@Autowired
	private GridImportDatabaseSupport gridImportSupport;
	@Autowired
	private GridReplicaViewManager gridViewManager;
	@Autowired
	private GridIndexManager gridIndexManger;
	
	
	@BeforeEach
	public void before() {
		gridIndexManger.truncateAll();
	}
	
	@Test
	@GridTransaction(readOnly = false)
	public void testCreateTemporaryTableFromCsv() throws IOException {
		GridHeader gridHeader = new GridHeader().setOrderedColumns(List.of(
			new Column().setName("a"),
			new Column().setName("b"),
			new Column().setName("c"),
			new Column().setName("d")
		));
		
		List<String> upsertKey = List.of("b", "a");
		
		long start = System.currentTimeMillis();

		InputStreamReader is = new InputStreamReader(GridImportDatabaseSupportIntegrationTest.class.getClassLoader().getResourceAsStream("grid_csv_import.csv"), StandardCharsets.UTF_8);
		CSVReader reader = CSVUtils.createCSVReader(is, null, null);

		try (is;reader) {
			gridImportSupport.createTemporaryTableFromCsv(reader, gridHeader, upsertKey);
		}
		
		System.out.println("Import took: "+(System.currentTimeMillis()-start)+"ms");
		
		Iterator<Object[]> it = gridImportSupport.getTemporaryTableIterator(GridImportDatabaseSupport.TEMP_TABLE_CSV);
		
		while (it.hasNext()) {
			System.out.println(Arrays.toString(it.next()));
		}
		
	}
	
	@Test
	//@GridTransaction(readOnly = false)
	public void testCreateTemporaryTableFromGridView() throws IOException {
		
		String sessionId = GridUtils.gridSessionIdAsString(123L);
		Long replicaId = 987L;
		
		List<ColumnModel> schema = List.of(
			new ColumnModel().setName("a").setColumnType(ColumnType.DOUBLE),
			new ColumnModel().setName("b").setColumnType(ColumnType.INTEGER),
			new ColumnModel().setName("c").setColumnType(ColumnType.STRING),
			new ColumnModel().setName("d").setColumnType(ColumnType.BOOLEAN)
		);
		
		List<String> upsertKey = List.of("b", "a");

		List<Row> rows = TableModelTestUtils.createRows(schema, 5_000);
		
		long start = System.currentTimeMillis();
		
		writeRowsAsPatches(rows, sessionId, replicaId, schema);
		
		System.out.println("Storage took: "+(System.currentTimeMillis()-start)+"ms");
		
		GridHeader gridHeader = gridViewManager.readHeader(sessionId, replicaId).orElseThrow();
		
		start = System.currentTimeMillis();
		
		Iterator<RowView> rowView = gridViewManager.getQueryIterator(gridHeader, Collections.emptyList());
		
		gridImportSupport.createTemporaryTableFromGrid(rowView, gridHeader, upsertKey);
		
		System.out.println("Import took: "+(System.currentTimeMillis()-start)+"ms");
		
		Iterator<Object[]> it = gridImportSupport.getTemporaryTableIterator(GridImportDatabaseSupport.TEMP_TABLE_GRID);
		
		while (it.hasNext()) {
			System.out.println(Arrays.toString(it.next()));
		}
	}
	
	void writeRowsAsPatches(List<Row> rows, String sessionId, Long replicaId, List<ColumnModel> schema) throws IOException {
		
		PatchRowHandler patchRowHandler = new PatchRowHandler((s, pid, body) -> {
			gridIndexManger.applyPatch(sessionId, pid.getReplicaId(), PatchCompactSerializable.deserialize(new JSONArray(body)));
			return true;
		}, sessionId, replicaId, schema, 1000L);
		
		try (patchRowHandler) {
			rows.stream().forEach(r -> {
				patchRowHandler.nextRow(r);
			});
		}
	}

}
