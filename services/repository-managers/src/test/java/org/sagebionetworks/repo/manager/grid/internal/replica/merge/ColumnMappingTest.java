package org.sagebionetworks.repo.manager.grid.internal.replica.merge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.Column;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;

class ColumnMappingTest {

	private ColumnModel colModel(String name, ColumnType type) {
		return new ColumnModel().setName(name).setColumnType(type);
	}

	private Column col(String name, int index) {
		return new Column().setName(name).setVectorIndex(index);
	}

	private ColumnMapping mapping(String name, ColumnType type, int csvIndex, int gridIndex, boolean isUpsert) {
		return new ColumnMapping(name, type, csvIndex, gridIndex, isUpsert);
	}

	@Test
	void testGetColumnMapping() {
		List<ColumnModel> csvSchema = List.of(
			colModel("id", ColumnType.STRING),
			colModel("foo", ColumnType.INTEGER),
			colModel("bar", ColumnType.DOUBLE)
		);
		List<Column> gridSchema = List.of(
			col("id", 0),
			col("foo", 1),
			col("bar", 2)
		);
		List<String> upsertKey = Collections.singletonList("id");

		ColumnMapping[] expected = new ColumnMapping[] {
			mapping("id", ColumnType.STRING, 0, 0, true),
			mapping("foo", ColumnType.INTEGER, 1, 1, false),
			mapping("bar", ColumnType.DOUBLE, 2, 2, false)
		};
		
		ColumnMapping[] result = ColumnMapping.getColumnMapping(csvSchema, gridSchema, upsertKey);
		
		assertArrayEquals(expected, result);
	}

	@Test
	void testGetColumnMappingWithUpsertKeyNotInCsvSchema() {
		List<ColumnModel> csvSchema = List.of(colModel("foo", ColumnType.STRING));
		List<Column> gridSchema = List.of(col("foo", 0));
		List<String> upsertKey = List.of("id");
		
		assertEquals("The upsert key column \"id\" does not exist in the CSV schema.", assertThrows(IllegalArgumentException.class, () -> {
			ColumnMapping.getColumnMapping(csvSchema, gridSchema, upsertKey);
		}).getMessage());
	}

	@Test
	void testGetColumnMappingWithUpsertKeyNotInGridSchema() {
		List<ColumnModel> csvSchema = List.of(colModel("id", ColumnType.STRING));
		List<Column> gridSchema = List.of(col("foo", 0));
		List<String> upsertKey = List.of("id");
		
		assertEquals("The upsert key column \"id\" does not exist in the grid schema.", assertThrows(IllegalArgumentException.class, () -> {
			ColumnMapping.getColumnMapping(csvSchema, gridSchema, upsertKey);
		}).getMessage());
	}

	@Test
	void testGetColumnMappingWithCsvColumnNotInGrid() {
		List<ColumnModel> csvSchema = List.of(
			colModel("id", ColumnType.STRING),
			colModel("foo", ColumnType.INTEGER),
			colModel("extra", ColumnType.DOUBLE)
		);
		List<Column> gridSchema = List.of(
			col("id", 0),
			col("foo", 1)
		);
		List<String> upsertKey = Collections.singletonList("id");
		ColumnMapping[] expected = new ColumnMapping[] {
			mapping("id", ColumnType.STRING, 0, 0, true),
			mapping("foo", ColumnType.INTEGER, 1, 1, false)
		};
		ColumnMapping[] result = ColumnMapping.getColumnMapping(csvSchema, gridSchema, upsertKey);
		assertArrayEquals(expected, result);
	}

	@Test
	void testGetColumnMappingWithMultiUpsertKey() {
		List<ColumnModel> csvSchema = List.of(
			colModel("foo", ColumnType.STRING),
			colModel("bar", ColumnType.INTEGER),
			colModel("baz", ColumnType.DOUBLE)
		);
		List<Column> gridSchema = List.of(
			col("foo", 0),
			col("bar", 1),
			col("baz", 2)
		);
		List<String> upsertKey = List.of("bar", "foo");
		ColumnMapping[] expected = new ColumnMapping[] {
			mapping("bar", ColumnType.INTEGER, 1, 1, true),
			mapping("foo", ColumnType.STRING, 0, 0, true),
			mapping("baz", ColumnType.DOUBLE, 2, 2, false)
		};
		ColumnMapping[] result = ColumnMapping.getColumnMapping(csvSchema, gridSchema, upsertKey);
		assertArrayEquals(expected, result);
	}
	
	@Test
	void testGetColumnMappingWithOutOfOrder() {
		
		List<ColumnModel> csvSchema = List.of(
			colModel("foo", ColumnType.STRING),
			colModel("id", ColumnType.INTEGER),
			colModel("bar", ColumnType.DOUBLE)
		);
		
		List<Column> gridSchema = List.of(
			col("id", 0),
			col("bar", 2),
			col("foo", 1)
		);
		
		List<String> upsertKey = List.of("bar", "id");

		ColumnMapping[] expected = new ColumnMapping[] {
			mapping("bar", ColumnType.DOUBLE, 2, 1, true),
			mapping("id", ColumnType.INTEGER, 1, 0, true),
			mapping("foo", ColumnType.STRING, 0, 2, false)
		};
		
		ColumnMapping[] result = ColumnMapping.getColumnMapping(csvSchema, gridSchema, upsertKey);
		
		assertArrayEquals(expected, result);
	}
}