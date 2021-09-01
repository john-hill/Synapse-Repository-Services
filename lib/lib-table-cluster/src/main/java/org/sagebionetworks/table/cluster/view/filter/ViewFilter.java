package org.sagebionetworks.table.cluster.view.filter;


import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * Abstraction for an immutable filter that defines the rows of a view.
 *
 */
public interface ViewFilter {
	
	/**
	 * Returns true when nothing will match this filter.
	 * @return
	 */
	boolean isEmpty();
	
	/**
	 * The SQL parameters for all bindings the filter SQL.
	 * @return
	 */
	MapSqlParameterSource getParameters();
	
	/**
	 * The SQL that defines this view's filter.
	 * @return
	 */
	String getFilterSql();
	
	/**
	 * Builder to build a new filter from the existing filter.
	 * @return
	 */
	Builder newBuilder();
	
}