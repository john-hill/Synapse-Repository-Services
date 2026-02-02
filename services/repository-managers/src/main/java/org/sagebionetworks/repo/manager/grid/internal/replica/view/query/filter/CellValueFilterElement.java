package org.sagebionetworks.repo.manager.grid.internal.replica.view.query.filter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.json.JSONArray;
import org.json.JSONObject;
import org.sagebionetworks.repo.manager.grid.internal.replica.view.query.Context;
import org.sagebionetworks.repo.model.grid.query.CellValueFilter;
import org.sagebionetworks.repo.model.grid.query.Filter;
import org.sagebionetworks.util.ValidateArgument;

public class CellValueFilterElement implements FilterElement {

	private String columnName;
	private CellValueOperatorElement operator;
	private Object value;

	public CellValueFilterElement(Filter filter) {
		this((CellValueFilter) filter);
	}

	public CellValueFilterElement(CellValueFilter filter) {
		ValidateArgument.required(filter, "filter");
		ValidateArgument.required(filter.getOperator(), "filter.operator");
		this.columnName = filter.getColumnName();
		this.operator = CellValueOperatorElement.valueOf(filter.getOperator().name());
		this.value = filter.getValue();
	}

	public CellValueFilterElement() {
	}

	// Getters and setters with method chaining
	public String getColumnName() {
		return columnName;
	}

	public CellValueFilterElement setColumnName(String columnName) {
		this.columnName = columnName;
		return this;
	}

	public CellValueOperatorElement getOperator() {
		return operator;
	}

	public CellValueFilterElement setOperator(CellValueOperatorElement operator) {
		this.operator = operator;
		return this;
	}

	public Object getValue() {
		return value;
	}

	public CellValueFilterElement setValue(Object value) {
		this.value = value;
		return this;
	}

	public CellValueFilterElement setValue(List<Object> value) {
		this.value = new JSONArray(value);
		return this;
	}

	@Override
	public void toSql(StringBuilder sqlBuilder, Map<String, Object> params, Context context) {
		validateInputs(sqlBuilder, params, context);

		String bind = "val" + params.size();
		Integer columnIndex = context.getColumnIndexForName(columnName);

		switch (operator.getValueMultiplicity()) {
		case one:
			handleSingleValue(sqlBuilder, params, bind, columnIndex);
			break;
		case many:
			handleMultipleValues(sqlBuilder, params, bind, columnIndex);
			break;
		case none:
			handleNoValue(sqlBuilder, columnIndex);
			break;
		default:
			throw new IllegalArgumentException("Unknown operation: " + operator);
		}
	}

	private void validateInputs(StringBuilder sqlBuilder, Map<String, Object> params, Context context) {
		ValidateArgument.required(sqlBuilder, "sqlBuilder");
		ValidateArgument.required(params, "params");
		ValidateArgument.required(context, "context");
		ValidateArgument.required(context.getHeader(), "context.header");
		ValidateArgument.required(context.getHeader().getOrderedColumns(), "context.header.orderedColumns");
		ValidateArgument.required(columnName, "columnName");
		ValidateArgument.required(operator, "operator");
	}

	private void handleSingleValue(StringBuilder sqlBuilder, Map<String, Object> params, String bind,
			Integer columnIndex) {
		if (value == null) {
			throw new IllegalArgumentException("Expected exactly one value for operation: " + operator);
		}
		sqlBuilder.append("(");
		if (CellValueOperatorElement.NOT_EQUALS.equals(operator) || CellValueOperatorElement.NOT_LIKE.equals(operator)) {
			// If the check is "!=" or "NOT LIKE", then we want to include undefined/null/empty values, which will never match the passed value
			sqlBuilder.append(" VALS->'$[").append(columnIndex).append("].v' IS NULL OR");
			sqlBuilder.append(" JSON_LENGTH(VALS, '$[").append(columnIndex).append("].v') != 1 OR");
			sqlBuilder.append(" JSON_TYPE(VALS->'$[").append(columnIndex).append("].v[0]') = 'NULL' OR");
		} else {
			// For all other operators, we want to exclude undefined/null values, which cannot be compared to the passed value
			sqlBuilder.append(" JSON_LENGTH(VALS, '$[").append(columnIndex).append("].v') = 1 AND");
			sqlBuilder.append(" JSON_TYPE(VALS->'$[").append(columnIndex).append("].v[0]') != 'NULL' AND");
		}

		String function = isString(value) ? "->>" : "->";
		sqlBuilder.append(" VALS").append(function).append("'$[").append(columnIndex).append("].v[0]' ").append(operator.toSql());

		if (isJsonType(value)) {
			sqlBuilder.append(" CAST(:").append(bind).append(" AS JSON)");
			params.put(bind, value.toString());
		} else {
			sqlBuilder.append(" :").append(bind);
			params.put(bind, value);
		}
		sqlBuilder.append(")");
	}

	private void handleMultipleValues(StringBuilder sqlBuilder, Map<String, Object> params, String bind,
			Integer columnIndex) {
		if (!(value instanceof JSONArray) || ((JSONArray) value).length() == 0) {
			throw new IllegalArgumentException("Expected at least one value for operation: " + operator);
		}

		sqlBuilder.append("(");

		if (CellValueOperatorElement.NOT_IN.equals(operator)) {
			// If the check is "NOT IN", then we want to include undefined/null/empty values, which will never match the passed values
			sqlBuilder.append(" VALS->'$[").append(columnIndex).append("].v' IS NULL OR");
			sqlBuilder.append(" JSON_LENGTH(VALS, '$[").append(columnIndex).append("].v') != 1 OR");
			sqlBuilder.append(" JSON_TYPE(VALS->'$[").append(columnIndex).append("].v[0]') = 'NULL' OR");
		} else {
			// For all other operators ("IN"), we want to exclude undefined/null values, which cannot be compared to the passed value
			sqlBuilder.append(" JSON_LENGTH(VALS, '$[").append(columnIndex).append("].v') = 1 AND");
			sqlBuilder.append(" JSON_TYPE(VALS->'$[").append(columnIndex).append("].v[0]') != 'NULL' AND");
		}


		sqlBuilder.append(" VALS->'$[").append(columnIndex).append("].v[0]' ").append(operator.toSql());
		sqlBuilder.append(" (:").append(bind).append(")");

		List<Object> toBind = new ArrayList<>();
		((JSONArray) value).forEach(o -> {
			if (isJsonType(o)) {
				toBind.add(o.toString());
			} else {
				toBind.add(o);
			}
		});
		params.put(bind, toBind);
		sqlBuilder.append(")");
	}

	private void handleNoValue(StringBuilder sqlBuilder, Integer columnIndex) {
		if (value != null && !JSONObject.NULL.equals(value)) {
			throw new IllegalArgumentException("Expected no value for operator: " + operator);
		}

		if (CellValueOperatorElement.IS_NOT_NULL.equals(operator)) {
			// IS_NOT_NULL matches empty/undefined cells (they are "not null", just missing)
			sqlBuilder.append("(");
			sqlBuilder.append(" VALS->'$[").append(columnIndex).append("].v' IS NULL OR");
			sqlBuilder.append(" JSON_LENGTH(VALS, '$[").append(columnIndex).append("].v') != 1 OR");
			sqlBuilder.append(" JSON_TYPE(VALS->'$[").append(columnIndex).append("].v[0]') != 'NULL'");
			sqlBuilder.append(")");
		} else if (CellValueOperatorElement.IS_NULL.equals(operator)) {
			// IS_NULL only matches explicit JSON null values
			sqlBuilder.append("(");
			sqlBuilder.append(" JSON_LENGTH(VALS, '$[").append(columnIndex).append("].v') = 1 AND");
			sqlBuilder.append(" JSON_TYPE(VALS->'$[").append(columnIndex).append("].v[0]') = 'NULL'");
			sqlBuilder.append(")");
		} else if (CellValueOperatorElement.IS_UNDEFINED.equals(operator)) {
			// IS_UNDEFINED matches empty/missing cells or explicit undefined values
			sqlBuilder.append("(");
			sqlBuilder.append(" VALS->'$[").append(columnIndex).append("].v' IS NULL OR");
			sqlBuilder.append(" JSON_LENGTH(VALS, '$[").append(columnIndex).append("].v') != 1");
			sqlBuilder.append(")");
		} else if (CellValueOperatorElement.IS_DEFINED.equals(operator)) {
			// IS_DEFINED matches cells with actual values (not empty, not undefined)
			sqlBuilder.append("(");
			sqlBuilder.append(" VALS->'$[").append(columnIndex).append("].v' IS NOT NULL AND");
			sqlBuilder.append(" JSON_LENGTH(VALS, '$[").append(columnIndex).append("].v') = 1");
			sqlBuilder.append(")");
		} else {
			// Other no-value operators
			sqlBuilder.append(" VALS->'$[").append(columnIndex).append("].v' ").append(operator.toSql());
		}
	}

	private boolean isJsonType(Object val) {
		return val instanceof JSONArray || val instanceof Boolean || val instanceof JSONObject;
	}

	private boolean isString(Object val) {
		return val instanceof String;
	}

	@Override
	public int hashCode() {
		return Objects.hash(columnName, operator, value);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null || getClass() != obj.getClass())
			return false;
		CellValueFilterElement other = (CellValueFilterElement) obj;
		return Objects.equals(columnName, other.columnName) && operator == other.operator
				&& Objects.equals(value, other.value);
	}

	@Override
	public String toString() {
		return "CellValueFilterElement [columnName=" + columnName + ", operator=" + operator + ", value=" + value + "]";
	}
}
