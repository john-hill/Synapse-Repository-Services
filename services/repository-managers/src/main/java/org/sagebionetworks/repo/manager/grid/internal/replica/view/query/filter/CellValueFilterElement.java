package org.sagebionetworks.repo.manager.grid.internal.replica.view.query.filter;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.sagebionetworks.repo.manager.grid.internal.replica.view.query.Context;
import org.sagebionetworks.repo.model.grid.query.CellValueFilter;
import org.sagebionetworks.repo.model.grid.query.Filter;
import org.sagebionetworks.util.ValidateArgument;

public class CellValueFilterElement implements FilterElement {

	private String columnName;
	private CellValueOperatorElement operator;
	private List<Object> value;
	
	public CellValueFilterElement(Filter filter) {
		this((CellValueFilter) filter);
	}

	public CellValueFilterElement(CellValueFilter filter) {
		ValidateArgument.required(filter, "filter");
		columnName = filter.getColumnName();
		operator = CellValueOperatorElement.valueOf(filter.getOperator().name());
		value = Collections.unmodifiableList(filter.getValue());
	}

	public CellValueFilterElement() {
	}

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

	public List<Object> getValue() {
		return value;
	}

	public CellValueFilterElement setValue(Object... value) {
		this.value = Arrays.asList(value);
		return this;
	}

	public CellValueFilterElement setValue(List<Object> value) {
		this.value = value;
		return this;
	}

	@Override
	public void toSql(StringBuilder sqlBuilder, Map<String, Object> params, Context context) {
		ValidateArgument.required(sqlBuilder, "sqlBuilder");
		ValidateArgument.required(params, "params");
		ValidateArgument.required(context, "context");
		ValidateArgument.required(context.getHeader(), "context.header");
		ValidateArgument.required(context.getHeader().getOrderedColumns(), "context.header.orderedColumns");
		ValidateArgument.required(columnName, "columnName");
		ValidateArgument.required(operator, "operator");

		int index = params.size();
		String bind = String.format("val%d", index);
		Integer columnIndex = context.getHeader().getOrderedColumns().stream()
				.filter(c -> c.getName().equals(columnName)).map(c -> c.getVectorIndex()).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("Column name not found: " + columnName));

		sqlBuilder.append(" VALS->>'$[").append(columnIndex).append("]' ").append(operator.toSql());

		switch (operator.getValueMultiplicity()) {
		case one:
			if (value == null || value.size() != 1) {
				throw new IllegalArgumentException("Expected exactly one value for operation: " + operator);
			}
			sqlBuilder.append(" :").append(bind);
			params.put(bind, value.get(0));
			break;
		case many:
			if (value == null || value.isEmpty()) {
				throw new IllegalArgumentException("Expected at least one value for operation: " + operator);
			}
			sqlBuilder.append(String.format(" (:%s)", bind));
			params.put(bind, value);
			break;
		case none:
			if (value != null && !value.isEmpty()) {
				throw new IllegalArgumentException("Expected no value for operator: " + operator);
			}
			break;
		default:
			throw new IllegalArgumentException("Unknown operation: " + operator);
		}
	}

	@Override
	public int hashCode() {
		return Objects.hash(columnName, operator, value);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
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
