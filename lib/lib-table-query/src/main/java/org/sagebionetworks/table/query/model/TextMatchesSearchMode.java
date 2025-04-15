package org.sagebionetworks.table.query.model;

public enum TextMatchesSearchMode {
	NATURAL_LANGUAGE("IN NATURAL LANGUAGE MODE"),
	BOOLEAN("IN BOOLEAN MODE");

	private String sql;
	
	TextMatchesSearchMode(String sql) {
		this.sql = sql;
	}
	
	public String getSql() {
		return sql;
	}
}
