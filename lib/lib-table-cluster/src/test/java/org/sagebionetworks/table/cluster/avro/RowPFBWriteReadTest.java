package org.sagebionetworks.table.cluster.avro;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.avro.file.SeekableByteArrayInput;
import org.junit.jupiter.api.Test;
import org.sagebionetworks.repo.model.dbo.dao.table.TableModelTestUtils;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.Row;

public class RowPFBWriteReadTest {

	@Test
	public void testAllTypesWriteAndRead() throws IOException {
		boolean hasDefault = false;
		String tableName = "foo";
		List<ColumnModel> columns = TableModelTestUtils.createOneOfEachType(hasDefault);
		List<Row> rows = TableModelTestUtils.createRows(columns, 10,
				new TableModelTestUtils.ValueOptions().includeSpace(false));
		// write
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (RowPFBWriter writer = new RowPFBWriter(tableName, columns, out)) {
			rows.forEach(r -> {
				writer.nextRow(r);
			});
		}

		// Read
		List<Row> result = new ArrayList<>();
		try (RowPFBReader reader = new RowPFBReader(new SeekableByteArrayInput(out.toByteArray()))) {
			while (reader.hasNext()) {
				result.add(reader.next());
			}
		}
		assertEquals(rows, result);
	}

}
