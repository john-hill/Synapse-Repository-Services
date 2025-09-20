package org.sagebionetworks.repo.manager.grid.internal.replica.view.query.filter;

import java.util.Map;

import org.sagebionetworks.repo.manager.grid.internal.replica.view.query.Context;
import org.sagebionetworks.repo.model.grid.query.Filter;

public class RowIsValidFilterElement implements FilterElement {

	public RowIsValidFilterElement(Filter filter) {
		// TODO Auto-generated constructor stub
	}

	@Override
	public void toSql(StringBuilder sqlBuilder, Map<String, Object> params, Context context) {
		// TODO add real logic instead of appending a '1' acts as a no-op 
		sqlBuilder.append(" 1");
	}

}
