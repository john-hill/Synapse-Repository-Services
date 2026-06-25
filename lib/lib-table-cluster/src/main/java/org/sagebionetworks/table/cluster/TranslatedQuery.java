package org.sagebionetworks.table.cluster;

import java.util.List;
import java.util.Map;

import org.sagebionetworks.repo.model.table.SelectColumn;

/**
 * Common abstraction for any query that has already be translated and is ready
 * to be executed/cached.
 *
 */
public interface TranslatedQuery {

	/**
	 * Provides information about select statement to the user.
	 * @return
	 */
	List<SelectColumn> getSelectColumns();

	/**
	 * The query parameters.
	 * @return
	 */
	Map<String, ?> getParameters();

	/**
	 * The translated SQL that is ready to execute.
	 * @return
	 */
	String getOutputSQL();

	/**
	 * Does the query result include the ROW_ID and ROW_VERSION?
	 * @return
	 */
	boolean getIncludesRowIdAndVersion();

	/**
	 * Does the query result include the table's etag?
	 * @return
	 */
	boolean getIncludeEntityEtag();

	/**
	 * Get the single TableId from the query. Note: If the SQL includes a JOIN or UNION, this
	 * will return null.
	 * @return
	 */
	String getSingleTableId();
	
	/**
	 * Does the query result include the row's benefactorId? True for non-aggregate view queries.
	 * @return
	 */
	boolean getIncludeBenefactorId();

	/**
	 * The number of benefactor columns appended to the select after the defining-SQL
	 * columns, read positionally as the trailing values of each Row. Zero unless the
	 * query was built with {@code includeRowBenefactors}.
	 * @return
	 */
	int getRowBenefactorColumnCount();

	/**
	 * Get a hash for the table/view. A change in the hash can prevent a stale cache
	 * hit.
	 *
	 * @return
	 */
	String getTableHash();

}
