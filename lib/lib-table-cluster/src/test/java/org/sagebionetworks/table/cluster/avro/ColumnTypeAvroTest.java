package org.sagebionetworks.table.cluster.avro;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import java.util.stream.Collectors;

import org.apache.avro.Schema;
import org.apache.avro.Schema.Field;
import org.apache.avro.generic.GenericRecordBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.sagebionetworks.repo.model.dbo.dao.table.TableModelTestUtils;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;

public class ColumnTypeAvroTest {
	
	
	@ParameterizedTest
	@EnumSource(ColumnType.class)
	public void testMatch(ColumnType type) {
		// call under test
		assertNotNull(ColumnTypeAvro.matchType(type));
	}

	@ParameterizedTest
	@EnumSource(ColumnType.class)
	public void testNullValues(ColumnType type) {
		// call under test
		Schema schema = ColumnTypeAvro.toAvro("NullValues",
				List.of(new ColumnModel().setName("foo").setColumnType(type)));

		assertNotNull(schema);
		assertEquals("NullValues", schema.getName());
		List<String> names = schema.getFields().stream().map(f -> f.name()).collect(Collectors.toList());
		assertEquals(List.of("foo"), names);

		// This will fail if nulls are not allowed for this type
		new GenericRecordBuilder(schema).set("foo", null).build();
	}

	@Test
	public void testToAvroWithEachtypeWithDefaults() {
		boolean hasDefault = true;
		String tableName = "foo";
		List<ColumnModel> columns = TableModelTestUtils.createOneOfEachType(hasDefault);

		// call under test
		Schema result = ColumnTypeAvro.toAvro(tableName, columns);
		System.out.println(result);
		assertNotNull(result);
		columns.forEach(c -> {
			Field f = result.getField(c.getName());
			assertNotNull(f, "missing: " + c.getName());
			assertEquals(f.name(), c.getName());
		});
	}
	
	@Test
	public void testToAvroWithEachtypeWithNullDefault() {
		boolean hasDefault = false;
		String tableName = "foo";
		List<ColumnModel> columns = TableModelTestUtils.createOneOfEachType(hasDefault);

		// call under test
		Schema result = ColumnTypeAvro.toAvro(tableName, columns);
		assertNotNull(result);
		columns.forEach(c -> {
			Field f = result.getField(c.getName());
			assertNotNull(f, "missing: " + c.getName());
			assertEquals(f.name(), c.getName());
		});
	}

}
