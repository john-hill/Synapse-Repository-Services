package org.sagebionetworks.table.cluster.avro;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.sagebionetworks.repo.model.table.Row;

public class RowPFBUtilsTest {

	@Test
	public void testCreateEntiyId() {
		// call under test
		assertEquals("123", RowPFBUtils.createEntiyId(new Row().setRowId(123L)));
		assertEquals("123_456", RowPFBUtils.createEntiyId(new Row().setRowId(123L).setVersionNumber(456L)));
		assertNotNull(RowPFBUtils.createEntiyId(new Row()));
	}

	@Test
	public void testCreateEntityIdWithNullRow() {
		String message = assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			RowPFBUtils.createEntiyId(null);
		}).getMessage();
		assertEquals("row is required.", message);
	}
}
