package org.sagebionetworks.repo.manager.search;

import java.util.List;
import java.util.Map;

import org.sagebionetworks.repo.model.search.table.ColumnAnalyzerOverride;
import org.sagebionetworks.repo.model.search.table.SynonymSet;
import org.sagebionetworks.repo.model.search.table.TextAnalyzer;
import org.sagebionetworks.repo.model.table.ColumnModel;

/**
 * Provides search index configuration data to {@link OpenSearchManager} methods.
 * Implementations may load data lazily on first access, allowing callers that
 * don't need all configuration (e.g., simple queries) to avoid unnecessary DB lookups.
 */
public interface SearchIndexContextProvider {

	/**
	 * @return The column models defining the search index schema.
	 */
	List<ColumnModel> getColumns();

	/**
	 * @return The default analyzer qualified name, or null for platform defaults.
	 */
	String getDefaultAnalyzer();

	/**
	 * @return The resolved column analyzer overrides (may be empty).
	 */
	List<ColumnAnalyzerOverride> getColumnAnalyzerOverrides();

	/**
	 * @return Map of analyzer qualified name to TextAnalyzer for all analyzers
	 *         needed by the current operation.
	 */
	Map<String, TextAnalyzer> getAnalyzers();

	/**
	 * @return The resolved synonym sets (may be empty).
	 */
	List<SynonymSet> getSynonymSets();
}
