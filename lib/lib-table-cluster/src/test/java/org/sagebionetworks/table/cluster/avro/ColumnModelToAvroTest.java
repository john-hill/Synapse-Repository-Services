package org.sagebionetworks.table.cluster.avro;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;

import org.apache.avro.Schema;
import org.apache.avro.Schema.Field;
import org.junit.jupiter.api.Test;
import org.sagebionetworks.repo.model.dbo.dao.table.TableModelTestUtils;
import org.sagebionetworks.repo.model.table.ColumnModel;

public class ColumnModelToAvroTest {

	@Test
	public void testToAvroWithEachtype() {
		boolean hasDefault = true;
		String tableName = "foo";
		List<ColumnModel> columns =TableModelTestUtils.createOneOfEachType(hasDefault);
		
		// call under test
		Schema result = ColumnModelToAvro.toAvro(tableName, columns);
		assertNotNull(result);
		columns.forEach(c->{
			Field f = result.getField(c.getName());
			assertNotNull(f, "missing: "+c.getName());
			assertEquals(f.name(), c.getName());
		});
	}
}
