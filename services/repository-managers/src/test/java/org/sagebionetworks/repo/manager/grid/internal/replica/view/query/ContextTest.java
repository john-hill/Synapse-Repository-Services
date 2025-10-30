package org.sagebionetworks.repo.manager.grid.internal.replica.view.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.Column;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.GridHeader;

public class ContextTest {

	private GridHeader header;
	private List<Column> columns;

	@BeforeEach
	public void before() {
		columns = List.of(
			new Column().setName("a"),
			new Column().setName("b"),
			new Column().setName("c")
		);
		header = new GridHeader().setOrderedColumns(columns);
	}

	@Test
	public void testGetHeader() {
		Context context = new Context(header);
		
		// call under test
		GridHeader result = context.getHeader();
		
		assertEquals(header, result);
	}

	@Test
	public void testGetColumnIndexForName() {
		Context context = new Context(header);
		
		for (int i = 0; i < columns.size(); i++) {
			// call under test
			assertEquals(i, context.getColumnIndexForName(columns.get(i).getName()));
		}
	}

	@Test
	public void testGetColumnIndexForNameNotFound() {
		Context context = new Context(header);
		
		String columnName = "invalid";
		String message = assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			context.getColumnIndexForName(columnName);
		}).getMessage();
		
		assertEquals("Column name not found: invalid", message);
	}

	@Test
	public void testConstructorWithEmptyColumns() {
		header.setOrderedColumns(Collections.emptyList());
		
		// call under test
		Context context = new Context(header);
		
		assertEquals(header, context.getHeader());
		
		// Should throw exception when trying to get any column
		assertThrows(IllegalArgumentException.class, () -> {
			context.getColumnIndexForName("anyColumn");
		});
	}

	@Test
	public void testConstructorWithNullColumns() {
		header.setOrderedColumns(null);
		
		// call under test
		Context context = new Context(header);
		
		assertEquals(header, context.getHeader());
		
		// Should throw exception when trying to get any column
		assertThrows(IllegalArgumentException.class, () -> {
			context.getColumnIndexForName("anyColumn");
		});
	}
}
