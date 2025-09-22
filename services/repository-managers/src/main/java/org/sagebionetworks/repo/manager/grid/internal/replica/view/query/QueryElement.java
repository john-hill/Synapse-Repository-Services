package org.sagebionetworks.repo.manager.grid.internal.replica.view.query;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.sagebionetworks.repo.manager.grid.internal.replica.view.query.filter.FilterElement;
import org.sagebionetworks.repo.manager.grid.internal.replica.view.query.filter.FilterTranslation;
import org.sagebionetworks.repo.model.grid.query.Query;

public class QueryElement implements Element {

	private SelectClauseElement selectClause;
	private List<FilterElement> where;
	private List<ColumnNameElement> groupBy;
	private List<OrderByItemElement> orderBy;
	private Long limit;
	private Long offset;
	
	public QueryElement() {
	}

	public QueryElement(Query query) {
		this.selectClause = new SelectClauseElement(query.getSelect());
		if (query.getWhere() != null) {
			where = query.getWhere().stream().map(f -> FilterTranslation.translate(f)).collect(Collectors.toList());
		}
		if (query.getGroupBy() != null) {
			groupBy = query.getGroupBy().stream().map(c -> new ColumnNameElement(c)).collect(Collectors.toList());
		}
		if (query.getOrderBy() != null) {
			orderBy = query.getOrderBy().stream().map(o -> new OrderByItemElement(o)).collect(Collectors.toList());
		}
		this.limit = query.getLimit();
		this.offset = query.getOffset();
	}

	@Override
	public void toSql(StringBuilder sqlBuilder, Map<String, Object> params, Context context) {
		sqlBuilder.append("SELECT");
		if(selectClause == null) {
			sqlBuilder.append(" *");
		}else {
			selectClause.toSql(sqlBuilder, params, context);
		}
		sqlBuilder.append(" FROM GRID");
		if (where != null && !where.isEmpty()) {
			sqlBuilder.append(" WHERE");
			boolean isFirst = true;
			for (FilterElement filter : where) {
				if (!isFirst) {
					sqlBuilder.append(" AND");
				}
				filter.toSql(sqlBuilder, params, context);
				isFirst = false;
			}
		}
		if (groupBy != null) {
			sqlBuilder.append(" GROUP BY");
			boolean isFirst = true;
			for (ColumnNameElement column : groupBy) {
				if (!isFirst) {
					sqlBuilder.append(" , ");
				}
				column.toSql(sqlBuilder, params, context);
				isFirst = false;
			}
		}
		sqlBuilder.append(" ORDER BY");
		if (orderBy != null) {
			boolean isFirst = true;
			for (OrderByItemElement order : orderBy) {
				if (!isFirst) {
					sqlBuilder.append(" , ");
				}
				order.toSql(sqlBuilder, params, context);
				isFirst = false;
			}
		} else {
			sqlBuilder.append(" `INDEX` ASC");
		}
		if (limit != null) {
			sqlBuilder.append(" LIMIT :limit");
			params.put("limit", limit);
		}
		if (offset != null) {
			sqlBuilder.append(" OFFSET :offset");
			params.put("offset", offset);
		}
	}

	public SelectClauseElement getSelectClause() {
		return selectClause;
	}

	public QueryElement setSelectClause(SelectClauseElement selectClause) {
		this.selectClause = selectClause;
		return this;
	}

	public List<FilterElement> getWhere() {
		return where;
	}

	public QueryElement setWhere(List<FilterElement> where) {
		this.where = where;
		return this;
	}

	public List<ColumnNameElement> getGroupBy() {
		return groupBy;
	}

	public QueryElement setGroupBy(List<ColumnNameElement> groupBy) {
		this.groupBy = groupBy;
		return this;
	}

	public List<OrderByItemElement> getOrderBy() {
		return orderBy;
	}

	public QueryElement setOrderBy(List<OrderByItemElement> orderBy) {
		this.orderBy = orderBy;
		return this;
	}

	public Long getLimit() {
		return limit;
	}

	public QueryElement setLimit(Long limit) {
		this.limit = limit;
		return this;
	}

	public Long getOffset() {
		return offset;
	}

	public QueryElement setOffset(Long offset) {
		this.offset = offset;
		return this;
	}

	@Override
	public int hashCode() {
		return Objects.hash(groupBy, limit, offset, orderBy, selectClause, where);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		QueryElement other = (QueryElement) obj;
		return Objects.equals(groupBy, other.groupBy) && Objects.equals(limit, other.limit)
				&& Objects.equals(offset, other.offset) && Objects.equals(orderBy, other.orderBy)
				&& Objects.equals(selectClause, other.selectClause) && Objects.equals(where, other.where);
	}

	@Override
	public String toString() {
		return "QueryElement [selectClause=" + selectClause + ", where=" + where + ", groupBy=" + groupBy + ", orderBy="
				+ orderBy + ", limit=" + limit + ", offset=" + offset + "]";
	}

}
