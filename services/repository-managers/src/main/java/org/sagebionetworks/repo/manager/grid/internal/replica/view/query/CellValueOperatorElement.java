package org.sagebionetworks.repo.manager.grid.internal.replica.view.query;

public enum CellValueOperatorElement {

	EQUALS(" ="),
	NOT_EQUALS(" <>"),
	GREATER_THAN(" >"),
	LESS_THAN(" <"),
	GREATER_THAN_OR_EQUALS(" >="),
	LESS_THAN_OR_EQUALS(" <="),
	IN(" IN"),
	NOT_IN(" NOT IN"),
	LIKE(" LIKE"),
	NOT_LIKE(" NOT LIKE");

	private String sql;

	CellValueOperatorElement(String sql) {
		this.sql = sql;
	}
	
	public String toSql() {
		return sql;
	}
}
