package org.sagebionetworks.repo.manager.grid.internal.replica.merge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;

import org.json.JSONArray;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.Column;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.GridHeader;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.RowData;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.RowObject;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.RowView;
import org.sagebionetworks.repo.manager.grid.internal.replica.view.GridReplicaViewManager;
import org.sagebionetworks.repo.manager.grid.internal.replica.view.filter.MultiValuesViewFilter;
import org.sagebionetworks.repo.manager.grid.internal.replica.view.filter.ViewFilter;

@ExtendWith(MockitoExtension.class)
public class BatchMergeProcessorTest {
	
	@Mock
	private GridReplicaViewManager mockGridViewManager;

	private GridHeader gridHeader;
	
	private String[] csvHeader;
	
	private List<String> upsertKey;
	
	private BatchMergeProcessor processor;
	
	private List<String[]> batch;
	
	private ViewFilter expectedFilter;
	
	@BeforeEach
	public void before() {
		gridHeader = new GridHeader().setOrderedColumns(List.of(
			new Column().setName("int"),
			new Column().setName("double"),
			new Column().setName("string"),
			new Column().setName("boolean")
		));
		
		csvHeader = new String[] {"int", "double", "string", "boolean"};
		
		upsertKey = List.of("int", "double");
		
		batch = List.of(
			new String[] {"1", "1.1", "one", "true"},
			new String[] {"2", "2.2", "two", "false"},
			new String[] {"3", "3.3", "three", "true"}
		);
		
		expectedFilter = new MultiValuesViewFilter(
			List.of(
				new Column().setName("int"),
				new Column().setName("double")
			), 
			List.of(
				new Object[] { 1L, 1.1D },
				new Object[] { 2L, 2.2D },
				new Object[] { 3L, 3.3D }
			)
		);
		
		processor = new BatchMergeProcessor(mockGridViewManager, gridHeader, csvHeader, upsertKey);
	}
	
	@Test
	public void testBatchWithNoMatches() {
		
		when(mockGridViewManager.getQueryIterator(gridHeader, List.of(expectedFilter))).thenReturn(Collections.emptyIterator());
		
		batch.forEach(processor::next);
	
		// Call under test
		processor.flush();
		
		assertEquals(3, processor.getProcessedCount());
		assertEquals(3, processor.getCreatedCount());
		assertEquals(0, processor.getUpdatedCount());
	}
	
	@Test
	public void testBatchWithMatches() {
		
		when(mockGridViewManager.getQueryIterator(gridHeader, List.of(expectedFilter))).thenReturn(List.of(
			new RowView().setRowObject(new RowObject().setData(new RowData().setCells(new JSONArray("[1, 1.1, \"one\", false]")))),
			new RowView().setRowObject(new RowObject().setData(new RowData().setCells(new JSONArray("[3, 3.3, null, false]"))))
		).iterator());
		
		batch.forEach(processor::next);
	
		// Call under test
		processor.flush();
		
		assertEquals(3, processor.getProcessedCount());
		assertEquals(1, processor.getCreatedCount());
		assertEquals(2, processor.getUpdatedCount());
	}
	
	@Test
	public void testBatchWithOutOfOrderUpsertKey() {
		upsertKey = List.of("double", "int");
		
		processor = new BatchMergeProcessor(mockGridViewManager, gridHeader, csvHeader, upsertKey);
		
		expectedFilter = new MultiValuesViewFilter(
			List.of(
				new Column().setName("double"),
				new Column().setName("int")
			), 
			List.of(
				new Object[] { 1.1D, 1L },
				new Object[] { 2.2D, 2L },
				new Object[] { 3.3D, 3L }
			)
		);
		
		
		when(mockGridViewManager.getQueryIterator(gridHeader, List.of(expectedFilter))).thenReturn(Collections.emptyIterator());
		
		batch.forEach(processor::next);
	
		// Call under test
		processor.flush();
		
		assertEquals(3, processor.getProcessedCount());
		assertEquals(3, processor.getCreatedCount());
		assertEquals(0, processor.getUpdatedCount());
	}
	
	@Test
	public void testBatchWithOutOfOrderColumns() {
		csvHeader = new String[] {"double", "int", "boolean", "string"};
		
		batch = List.of(
			new String[] {"1.1", "1", "true", "one"},
			new String[] {"2.2", "2", "false", "two"},
			new String[] {"3.3", "3", "true", "three"}
		);
		
		processor = new BatchMergeProcessor(mockGridViewManager, gridHeader, csvHeader, upsertKey);		
		
		when(mockGridViewManager.getQueryIterator(gridHeader, List.of(expectedFilter))).thenReturn(Collections.emptyIterator());
		
		batch.forEach(processor::next);
	
		// Call under test
		processor.flush();
		
		assertEquals(3, processor.getProcessedCount());
		assertEquals(3, processor.getCreatedCount());
		assertEquals(0, processor.getUpdatedCount());
	}
	
	@Test
	public void testBatchWithNullValues() {
		
		batch = List.of(
			new String[] {"1", "1.1", "one", null},
			new String[] {"2", "2.2", null, null},
			new String[] {"3", "3.3", "three", null}
		);
		
		processor = new BatchMergeProcessor(mockGridViewManager, gridHeader, csvHeader, upsertKey);
		
		when(mockGridViewManager.getQueryIterator(gridHeader, List.of(expectedFilter))).thenReturn(Collections.emptyIterator());
		
		batch.forEach(processor::next);
	
		// Call under test
		processor.flush();
		
		assertEquals(3, processor.getProcessedCount());
		assertEquals(3, processor.getCreatedCount());
		assertEquals(0, processor.getUpdatedCount());
	}
	
	@Test
	public void testBatchWithCsvColumnsLessThanGrid() {
		csvHeader = new String[] {"int", "double", "string"};
		
		assertEquals("The CSV file must have at least as many columns as the grid.", assertThrows(IllegalArgumentException.class, () -> {
			processor = new BatchMergeProcessor(mockGridViewManager, gridHeader, csvHeader, upsertKey);
		}).getMessage());
	}
	
	@Test
	public void testBatchWithCsvColumnsMoreThanGrid() {
		csvHeader = new String[] {"int", "double", "string", "extra", "boolean"};
		
		assertEquals("The column: extra was not found in the grid schema.", assertThrows(IllegalArgumentException.class, () -> {
			processor = new BatchMergeProcessor(mockGridViewManager, gridHeader, csvHeader, upsertKey);
		}).getMessage());
	}
	
	@Test
	public void testBatchWithNoViewManager() {
		mockGridViewManager = null;
		
		assertThrows(IllegalArgumentException.class, () -> {
			processor = new BatchMergeProcessor(mockGridViewManager, gridHeader, csvHeader, upsertKey);
		});
	}
	
	@Test
	public void testBatchWithNoGridHeader() {
		gridHeader = null;
		
		assertThrows(IllegalArgumentException.class, () -> {
			processor = new BatchMergeProcessor(mockGridViewManager, gridHeader, csvHeader, upsertKey);
		});
	}
	
	@Test
	public void testBatchWithNoCsvHeader() {
		csvHeader = null;
		
		assertThrows(IllegalArgumentException.class, () -> {
			processor = new BatchMergeProcessor(mockGridViewManager, gridHeader, csvHeader, upsertKey);
		});
	}
	
	@Test
	public void testBatchWithNoUpsertKey() {
		upsertKey = null;
		
		assertThrows(IllegalArgumentException.class, () -> {
			processor = new BatchMergeProcessor(mockGridViewManager, gridHeader, csvHeader, upsertKey);
		});
	}
}
