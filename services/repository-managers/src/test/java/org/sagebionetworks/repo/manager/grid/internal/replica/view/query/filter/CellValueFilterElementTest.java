package org.sagebionetworks.repo.manager.grid.internal.replica.view.query.filter;

import static org.junit.Assert.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.Column;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.GridHeader;
import org.sagebionetworks.repo.manager.grid.internal.replica.view.query.Context;
import org.sagebionetworks.repo.model.grid.query.CellValueFilter;
import org.sagebionetworks.repo.model.grid.query.CellValueOperator;

public class CellValueFilterElementTest {

	private StringBuilder sqlBuilder;
	private Map<String, Object> params;
	private GridHeader header;
	private Context context;

	@BeforeEach
	public void before() {
		sqlBuilder = new StringBuilder();
		params = new HashMap<>();
		header = new GridHeader().setOrderedColumns(List.of(
				//
				new Column().setName("one").setVectorIndex(1),
				//
				new Column().setName("zero").setVectorIndex(0),
				//
				new Column().setName("two").setVectorIndex(2)));

		context = new Context(header);
	}

	@Test
	public void testCopyConstructor() {
		CellValueFilter apiFilter = new CellValueFilter().setColumnName("one").setOperator(CellValueOperator.EQUALS)
				.setValue(List.of("aString"));
		// call under test
		CellValueFilterElement filter = new CellValueFilterElement(apiFilter);
		CellValueFilterElement expected = new CellValueFilterElement().setColumnName("one")
				.setOperator(CellValueOperatorElement.EQUALS).setValue("aString");
		assertEquals(expected, filter);
	}

	@Test
	public void testToSqlWithEquals() {
		CellValueFilterElement filter = new CellValueFilterElement().setColumnName("one")
				.setOperator(CellValueOperatorElement.EQUALS).setValue("aString");

		// call under test
		filter.toSql(sqlBuilder, params, context);
		assertEquals(" VALS->>'$[1]' = :val0", sqlBuilder.toString());
		assertEquals("aString", params.get("val0"));
	}

	@Test
	public void testToSqlWithExistingParams() {
		CellValueFilterElement filter = new CellValueFilterElement().setColumnName("zero")
				.setOperator(CellValueOperatorElement.EQUALS).setValue("aString");
		params.put("otherParam", "other");

		// call under test
		filter.toSql(sqlBuilder, params, context);
		assertEquals(" VALS->>'$[0]' = :val1", sqlBuilder.toString());
		assertEquals("aString", params.get("val1"));
	}

	@Test
	public void testToSqlWithNotEquals() {
		CellValueFilterElement filter = new CellValueFilterElement().setColumnName("zero")
				.setOperator(CellValueOperatorElement.NOT_EQUALS).setValue(15L);
		params.put("otherParam", "other");

		// call under test
		filter.toSql(sqlBuilder, params, context);
		assertEquals(" VALS->>'$[0]' <> :val1", sqlBuilder.toString());
		assertEquals(15L, params.get("val1"));
	}

	@Test
	public void testToSqlWithGreaterThan() {
		CellValueFilterElement filter = new CellValueFilterElement().setColumnName("zero")
				.setOperator(CellValueOperatorElement.GREATER_THAN).setValue(15L);
		params.put("otherParam", "other");

		// call under test
		filter.toSql(sqlBuilder, params, context);
		assertEquals(" VALS->>'$[0]' > :val1", sqlBuilder.toString());
		assertEquals(15L, params.get("val1"));
	}

	@Test
	public void testToSqlWithGreaterThanOrEquals() {
		CellValueFilterElement filter = new CellValueFilterElement().setColumnName("zero")
				.setOperator(CellValueOperatorElement.GREATER_THAN_OR_EQUALS).setValue(15L);
		params.put("otherParam", "other");

		// call under test
		filter.toSql(sqlBuilder, params, context);
		assertEquals(" VALS->>'$[0]' >= :val1", sqlBuilder.toString());
		assertEquals(15L, params.get("val1"));
	}

	@Test
	public void testToSqlWithLessThan() {
		CellValueFilterElement filter = new CellValueFilterElement().setColumnName("zero")
				.setOperator(CellValueOperatorElement.LESS_THAN).setValue(15L);
		params.put("otherParam", "other");

		// call under test
		filter.toSql(sqlBuilder, params, context);
		assertEquals(" VALS->>'$[0]' < :val1", sqlBuilder.toString());
		assertEquals(15L, params.get("val1"));
	}

	@Test
	public void testToSqlWithLessThanOrEquals() {
		CellValueFilterElement filter = new CellValueFilterElement().setColumnName("zero")
				.setOperator(CellValueOperatorElement.LESS_THAN_OR_EQUALS).setValue(15L);
		params.put("otherParam", "other");

		// call under test
		filter.toSql(sqlBuilder, params, context);
		assertEquals(" VALS->>'$[0]' <= :val1", sqlBuilder.toString());
		assertEquals(15L, params.get("val1"));
	}

	@Test
	public void testToSqlWithLike() {
		CellValueFilterElement filter = new CellValueFilterElement().setColumnName("zero")
				.setOperator(CellValueOperatorElement.LIKE).setValue("%contains%");
		params.put("otherParam", "other");

		// call under test
		filter.toSql(sqlBuilder, params, context);
		assertEquals(" VALS->>'$[0]' LIKE :val1", sqlBuilder.toString());
		assertEquals("%contains%", params.get("val1"));
	}

	@Test
	public void testToSqlWithNotLike() {
		CellValueFilterElement filter = new CellValueFilterElement().setColumnName("zero")
				.setOperator(CellValueOperatorElement.NOT_LIKE).setValue("%contains%");
		params.put("otherParam", "other");

		// call under test
		filter.toSql(sqlBuilder, params, context);
		assertEquals(" VALS->>'$[0]' NOT LIKE :val1", sqlBuilder.toString());
		assertEquals("%contains%", params.get("val1"));
	}

	@Test
	public void testToSqlWithIn() {
		CellValueFilterElement filter = new CellValueFilterElement().setColumnName("zero")
				.setOperator(CellValueOperatorElement.IN).setValue(1, 2, 3);
		params.put("otherParam", "other");

		// call under test
		filter.toSql(sqlBuilder, params, context);
		assertEquals(" VALS->>'$[0]' IN (:val1)", sqlBuilder.toString());
		assertEquals(List.of(1, 2, 3), params.get("val1"));
	}

	@Test
	public void testToSqlWithNotIn() {
		CellValueFilterElement filter = new CellValueFilterElement().setColumnName("zero")
				.setOperator(CellValueOperatorElement.NOT_IN).setValue(1, 2, 3);
		params.put("otherParam", "other");

		// call under test
		filter.toSql(sqlBuilder, params, context);
		assertEquals(" VALS->>'$[0]' NOT IN (:val1)", sqlBuilder.toString());
		assertEquals(List.of(1, 2, 3), params.get("val1"));
	}

	@Test
	public void testToSqlWithIsNull() {
		CellValueFilterElement filter = new CellValueFilterElement().setColumnName("zero")
				.setOperator(CellValueOperatorElement.IS_NULL);
		params.put("otherParam", "other");

		// call under test
		filter.toSql(sqlBuilder, params, context);
		assertEquals(" VALS->>'$[0]' IS NULL", sqlBuilder.toString());
	}

	@Test
	public void testToSqlWithIsNotNull() {
		CellValueFilterElement filter = new CellValueFilterElement().setColumnName("two")
				.setOperator(CellValueOperatorElement.IS_NOT_NULL);
		params.put("otherParam", "other");

		// call under test
		filter.toSql(sqlBuilder, params, context);
		assertEquals(" VALS->>'$[2]' IS NOT NULL", sqlBuilder.toString());
	}

	@Test
	public void testToSqlWithColumnDoesNotExist() {
		CellValueFilterElement filter = new CellValueFilterElement().setColumnName("doesNotExist")
				.setOperator(CellValueOperatorElement.IS_NOT_NULL);
		params.put("otherParam", "other");

		String message = assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			filter.toSql(sqlBuilder, params, context);
		}).getMessage();
		assertEquals("Column name not found: doesNotExist", message);
	}

	@ParameterizedTest
	@EnumSource(value = CellValueOperatorElement.class, names = { "EQUALS", "NOT_EQUALS", "LESS_THAN",
			"LESS_THAN_OR_EQUALS", "GREATER_THAN", "GREATER_THAN_OR_EQUALS", "LIKE", "NOT_LIKE" })
	public void testToSqlWithWithMultiplictyOne(CellValueOperatorElement operator) {

		CellValueFilterElement filter = new CellValueFilterElement().setColumnName("two").setOperator(operator)
				.setValue(4);

		// call under test
		filter.toSql(sqlBuilder, params, context);
		assertEquals(" VALS->>'$[2]' " + operator.toSql() + " :val0", sqlBuilder.toString());
		assertEquals(4, params.get("val0"));

		CellValueFilterElement filterTooMany = new CellValueFilterElement().setColumnName("two").setOperator(operator)
				.setValue(1, 2, 3);
		params.put("otherParam", "other");

		String message = assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			filterTooMany.toSql(sqlBuilder, params, context);
		}).getMessage();
		assertEquals("Expected exactly one value for operation: " + operator, message);

		CellValueFilterElement filterValueEmpty = new CellValueFilterElement().setColumnName("two")
				.setOperator(operator).setValue(Collections.emptyList());

		message = assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			filterValueEmpty.toSql(sqlBuilder, params, context);
		}).getMessage();
		assertEquals("Expected exactly one value for operation: " + operator, message);

		CellValueFilterElement filterValueNull = new CellValueFilterElement().setColumnName("two").setOperator(operator)
				.setValue((List) null);

		message = assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			filterValueNull.toSql(sqlBuilder, params, context);
		}).getMessage();
		assertEquals("Expected exactly one value for operation: " + operator, message);
	}

	@ParameterizedTest
	@EnumSource(value = CellValueOperatorElement.class, names = { "IN", "NOT_IN" })
	public void testToSqlWithWithMultiplictyOfMany(CellValueOperatorElement operator) {

		CellValueFilterElement filter = new CellValueFilterElement().setColumnName("two").setOperator(operator)
				.setValue(4, 5, 6);

		// call under test
		filter.toSql(sqlBuilder, params, context);
		assertEquals(" VALS->>'$[2]' " + operator.toSql() + " (:val0)", sqlBuilder.toString());
		assertEquals(List.of(4, 5, 6), params.get("val0"));

		CellValueFilterElement filterValueEmpty = new CellValueFilterElement().setColumnName("two")
				.setOperator(operator).setValue(Collections.emptyList());

		String message = assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			filterValueEmpty.toSql(sqlBuilder, params, context);
		}).getMessage();
		assertEquals("Expected at least one value for operation: " + operator, message);

		CellValueFilterElement filterValueNull = new CellValueFilterElement().setColumnName("two").setOperator(operator)
				.setValue((List) null);

		message = assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			filterValueNull.toSql(sqlBuilder, params, context);
		}).getMessage();
		assertEquals("Expected at least one value for operation: " + operator, message);
	}

	@ParameterizedTest
	@EnumSource(value = CellValueOperatorElement.class, names = { "IS_NULL", "IS_NOT_NULL" })
	public void testToSqlWithWithMultiplictyOfNone(CellValueOperatorElement operator) {

		CellValueFilterElement filter = new CellValueFilterElement().setColumnName("two").setOperator(operator);

		// call under test
		filter.toSql(sqlBuilder, params, context);
		assertEquals(" VALS->>'$[2]' " + operator.toSql(), sqlBuilder.toString());

		CellValueFilterElement filterValueEmpty = new CellValueFilterElement().setColumnName("two")
				.setOperator(operator).setValue(Collections.emptyList());

		sqlBuilder = new StringBuilder();
		// call under test
		filterValueEmpty.toSql(sqlBuilder, params, context);
		assertEquals(" VALS->>'$[2]' " + operator.toSql(), sqlBuilder.toString());

		CellValueFilterElement filterValueNull = new CellValueFilterElement().setColumnName("two").setOperator(operator)
				.setValue((List) null);

		sqlBuilder = new StringBuilder();
		// call under test
		filterValueNull.toSql(sqlBuilder, params, context);
		assertEquals(" VALS->>'$[2]' " + operator.toSql(), sqlBuilder.toString());

		CellValueFilterElement filterNotEmpty = new CellValueFilterElement().setColumnName("two").setOperator(operator)
				.setValue(2);

		String message = assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			filterNotEmpty.toSql(sqlBuilder, params, context);
		}).getMessage();
		assertEquals("Expected no value for operator: " + operator, message);
	}

}
