package org.sagebionetworks.repo.manager.grid.internal.replica.view.query.filter;

import java.util.Map;

import org.sagebionetworks.repo.manager.grid.internal.replica.view.query.Context;
import org.sagebionetworks.repo.model.grid.query.Filter;
import org.sagebionetworks.repo.model.grid.query.RowSelectionFilter;

public class RowSelectionFilterElement implements FilterElement {

	private Boolean isSelected;

	public RowSelectionFilterElement(Filter filter) {
		this((RowSelectionFilter) filter);
	}

	public RowSelectionFilterElement(RowSelectionFilter filter) {
		this.isSelected = filter.getIsSelected();
	}

	@Override
	public void toSql(StringBuilder sqlBuilder, Map<String, Object> params, Context context) {
		// TODO add real logic instead of appending a '1' acts as a no-op 
		sqlBuilder.append(" 1");
	}

}
