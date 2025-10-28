package org.sagebionetworks.repo.manager.grid.internal.replica.view.query.select;

import java.util.List;
import java.util.Map;

import org.sagebionetworks.repo.manager.grid.internal.replica.view.query.Context;
import org.sagebionetworks.repo.model.grid.query.SelectColumn;
import org.sagebionetworks.repo.model.grid.query.SelectItem;

public class SelectColumnElement implements SelectItemElement {
	
	private String columnName;

	public SelectColumnElement(SelectItem item) {
		this.columnName = ((SelectColumn) item).getColumnName();
	}

	@Override
	public void toSql(StringBuilder sqlBuilder, Map<String, Object> params, Context context) {
		Integer columnIndex = context.getColumnIndexForName(columnName);
		sqlBuilder.append(String.format("VALS->'$[%d]'", columnIndex));
	}

	@Override
	public boolean isAggregate() {
		return false;
	}

	@Override
	public void setSelect(Context context, Long index, List<org.sagebionetworks.repo.model.grid.query.result.SelectColumn> selectColumns) {
		Integer vectorIndex = context.getColumnVectorIndexForName(columnName);		
		selectColumns.add(new org.sagebionetworks.repo.model.grid.query.result.SelectColumn()
			.setColumnName(columnName)
			.setColumnIndex(Long.valueOf(vectorIndex)));
	}

}
