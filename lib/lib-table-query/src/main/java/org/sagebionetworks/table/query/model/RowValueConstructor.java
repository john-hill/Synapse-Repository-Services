package org.sagebionetworks.table.query.model;

import java.util.List;


/**
 * This matches &ltrow value constructor&gt  in: <a href="https://github.com/ronsavage/SQL/blob/master/sql-92.bnf">SQL-92</a>
 */
public class RowValueConstructor extends SQLElement {

	RowValueConstructorElement rowValueConstructorElement;
	RowValueConstructorList rowValueConstructorList;
	public RowValueConstructor(
			RowValueConstructorElement rowValueConstructorElement) {
		super();
		this.rowValueConstructorElement = rowValueConstructorElement;
	}
	public RowValueConstructor(RowValueConstructorList rowValueConstructorList) {
		super();
		this.rowValueConstructorList = rowValueConstructorList;
	}
	public RowValueConstructorElement getRowValueConstructorElement() {
		return rowValueConstructorElement;
	}
	public RowValueConstructorList getRowValueConstructorList() {
		return rowValueConstructorList;
	}

	@Override
	public void toSql(StringBuilder builder, ToSqlParameters parameters) {
		if(rowValueConstructorElement != null){
			rowValueConstructorElement.toSql(builder, parameters);
		}else{
			builder.append("( ");
			rowValueConstructorList.toSql(builder, parameters);
			builder.append(" )");
		}
	}
	@Override
	<T extends Element> void addElements(List<T> elements, Class<T> type) {
		checkElement(elements, type, rowValueConstructorElement);
		checkElement(elements, type, rowValueConstructorList);
	}
}
