package org.sagebionetworks.repo.manager.table;

import java.util.List;

import org.sagebionetworks.repo.model.entity.IdAndVersion;
import org.sagebionetworks.repo.model.table.VirtualTable;
import org.sagebionetworks.table.cluster.QueryTranslator;

/**
 * Manager for operations on {@link VirtualTable}s
 */
public interface VirtualTableManager {

	/**
	 * Validates the SQL defining the given virtual table
	 * 
	 * @param materializedView
	 */
	void validate(VirtualTable virtualTable);

	/**
	 * Validates the given defining SQL
	 * 
	 * @param definingSql
	 */
	void validateDefiningSql(String definingSql);

	/**
	 * Builds a {@link QueryTranslator} for the given defining SQL
	 * 
	 * @param definingSql
	 * @return
	 */
	QueryTranslator buildQueryTranslator(String definingSql);

	/**
	 * Get the schema from the given SQL query
	 * 
	 * @param sqlQuery
	 * @return
	 */
	List<String> getSchemaIdsFromSqlQuery(QueryTranslator sqlQuery);

	/**
	 * Get the column IDs for this VT's schema.
	 * 
	 * @param idAndVersion
	 * @return
	 */
	List<String> getSchemaIds(IdAndVersion idAndVersion);

	/**
	 * Register the given defining SQL for the given VT
	 * 
	 * @param id
	 * @param definingSQL
	 */
	void registerDefiningSql(IdAndVersion id, String definingSQL);

}
