package org.sagebionetworks.repo.manager.grid.internal.replica.view.filter;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.stream.Collectors;

import org.sagebionetworks.repo.manager.grid.internal.replica.model.Column;

/**
 * A view filter that will limit the results to rows that match the provided cell
 * values for the specified columns.
 */
public class MultiValuesViewFilter implements ViewFilter {
	
	private List<Column> columns;
	private List<Object[]> values;

	public MultiValuesViewFilter(List<Column> columns, List<Object[]> values) {
		this.columns = columns;
		this.values = values;

	}

	@Override
	public String getConditionSql(int index) {
		StringJoiner inLeft = new StringJoiner(",", "(", ")");
		
		columns.forEach(column -> {
			inLeft.add(String.format("V1.VEC_VAL->>'$.c%d.v'", column.getVectorIndex()));
		});
		
		return String.format("%s IN (:cellValues%d)", inLeft, index);
	}

	@Override
	public String getParameterKey(int index) {
		return String.format("cellValues%d", index);
	}

	@Override
	public Object getParameterValue() {
		return values;
	}

	@Override
	public int hashCode() {
		return Objects.hash(columns, values.stream().mapToInt(Arrays::hashCode).toArray());
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null) {
			return false;
		}
		if (getClass() != obj.getClass()) {
			return false;
		}
		MultiValuesViewFilter other = (MultiValuesViewFilter) obj;
		
		if (!Objects.equals(columns, other.columns)) {
			return false;
		}
		
		if (values.size() != other.values.size())
			return false;
		for (int i = 0; i < values.size(); i++) {
			if (!Arrays.equals(values.get(i), other.values.get(i)))
				return false;
		}
		return true;
	}

	@Override
	public String toString() {
		return "MultiValuesViewFilter [columns=" + columns + ", values=" + values.stream().map(Arrays::toString).collect(Collectors.toList()) + "]";
	}

}
