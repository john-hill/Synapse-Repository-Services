package org.sagebionetworks.repo.manager.grid.internal.replica.view.query;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.sagebionetworks.repo.model.grid.query.CellValueFilter;

public class CellValueFilterElement implements FilterElement {

	private String columnName;
	private CellValueOperatorElement operator;
	private List<Object> value;

	private CellValueFilterElement(CellValueFilter filter) {
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

	public CellValueFilterElement setValue(List<Object> value) {
		this.value = value;
		return this;
	}

	@Override
	public void toSql(StringBuilder sqlBuilder, Map<String, Object> params, Context context) {
		int index = params.size();
		String bind = String.format("val%d", index);
		Integer columnIndex = context.getHeader().getOrderedColumns().stream()
				.filter(c -> c.getName().equals(columnName)).map(c -> c.getVectorIndex()).findFirst().get();
		sqlBuilder.append(String.format(" JSON_EXTRACT(VALS,'$[%d]') %s :%s", columnIndex, operator.toSql(), bind));
		params.put(bind, value.get(0));
	}

}
