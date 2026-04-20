package org.sagebionetworks.repo.manager.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.sagebionetworks.repo.model.dbo.search.ColumnAnalyzerOverrideDao;
import org.sagebionetworks.repo.model.dbo.search.SynonymSetDao;
import org.sagebionetworks.repo.model.dbo.search.TextAnalyzerDao;
import org.sagebionetworks.repo.model.search.table.ColumnAnalyzerOverride;
import org.sagebionetworks.repo.model.search.table.ColumnAnalyzerOverrideEntry;
import org.sagebionetworks.repo.model.search.table.SearchConfiguration;
import org.sagebionetworks.repo.model.search.table.SynonymSet;
import org.sagebionetworks.repo.model.search.table.TextAnalyzer;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;

/**
 * Implementation of {@link SearchIndexContextProvider} with two construction modes:
 * <ul>
 *   <li><b>Eager</b> ({@link #eager}): All data pre-loaded by the caller. Used for
 *       createIndex where everything is always needed.</li>
 *   <li><b>Lazy</b> ({@link #lazy}): Overrides, synonym sets, and analyzers loaded
 *       on first access from DAOs. Used for search/query where simple queries
 *       (keyword filters, MATCH_ALL) may never need analyzer resolution.</li>
 * </ul>
 */
public class SearchIndexContextProviderImpl implements SearchIndexContextProvider {

	private final SearchConfiguration config;
	private final List<ColumnModel> columns;

	// DAOs for lazy loading (null when using eager mode)
	private final SynonymSetDao synonymSetDao;
	private final ColumnAnalyzerOverrideDao columnAnalyzerOverrideDao;
	private final TextAnalyzerDao textAnalyzerDao;

	// Cached values — set eagerly or loaded lazily on first access
	private List<ColumnAnalyzerOverride> columnAnalyzerOverrides;
	private List<SynonymSet> synonymSets;
	private Map<String, TextAnalyzer> analyzers;

	private SearchIndexContextProviderImpl(SearchConfiguration config, List<ColumnModel> columns,
			List<ColumnAnalyzerOverride> columnAnalyzerOverrides, List<SynonymSet> synonymSets,
			Map<String, TextAnalyzer> analyzers,
			SynonymSetDao synonymSetDao, ColumnAnalyzerOverrideDao columnAnalyzerOverrideDao,
			TextAnalyzerDao textAnalyzerDao) {
		this.config = config;
		this.columns = columns;
		this.columnAnalyzerOverrides = columnAnalyzerOverrides;
		this.synonymSets = synonymSets;
		this.analyzers = analyzers;
		this.synonymSetDao = synonymSetDao;
		this.columnAnalyzerOverrideDao = columnAnalyzerOverrideDao;
		this.textAnalyzerDao = textAnalyzerDao;
	}

	/**
	 * Create a provider with all data pre-loaded. No lazy DB calls will occur.
	 * Use for createIndex where all configuration is always needed.
	 */
	public static SearchIndexContextProviderImpl eager(SearchConfiguration config, List<ColumnModel> columns,
			List<ColumnAnalyzerOverride> columnAnalyzerOverrides, List<SynonymSet> synonymSets,
			Map<String, TextAnalyzer> analyzers) {
		return new SearchIndexContextProviderImpl(config, columns,
				columnAnalyzerOverrides != null ? columnAnalyzerOverrides : Collections.emptyList(),
				synonymSets != null ? synonymSets : Collections.emptyList(),
				analyzers != null ? analyzers : Collections.emptyMap(),
				null, null, null);
	}

	/**
	 * Create a provider that lazily loads overrides, synonym sets, and analyzers
	 * from DAOs on first access. Use for search/query where simple queries may
	 * not need all configuration.
	 */
	public static SearchIndexContextProviderImpl lazy(SearchConfiguration config, List<ColumnModel> columns,
			ColumnAnalyzerOverrideDao columnAnalyzerOverrideDao, SynonymSetDao synonymSetDao,
			TextAnalyzerDao textAnalyzerDao) {
		return new SearchIndexContextProviderImpl(config, columns,
				null, null, null,
				synonymSetDao, columnAnalyzerOverrideDao, textAnalyzerDao);
	}

	@Override
	public List<ColumnModel> getColumns() {
		return columns;
	}

	@Override
	public String getDefaultAnalyzer() {
		return config != null ? config.getDefaultAnalyzer() : null;
	}

	@Override
	public List<ColumnAnalyzerOverride> getColumnAnalyzerOverrides() {
		if (columnAnalyzerOverrides == null) {
			columnAnalyzerOverrides = loadColumnAnalyzerOverrides();
		}
		return columnAnalyzerOverrides;
	}

	@Override
	public Map<String, TextAnalyzer> getAnalyzers() {
		if (analyzers == null) {
			analyzers = collectAndLoadAnalyzers();
		}
		return analyzers;
	}

	@Override
	public List<SynonymSet> getSynonymSets() {
		if (synonymSets == null) {
			synonymSets = loadSynonymSets();
		}
		return synonymSets;
	}

	private List<ColumnAnalyzerOverride> loadColumnAnalyzerOverrides() {
		if (config == null || config.getColumnAnalyzerOverrides() == null
				|| config.getColumnAnalyzerOverrides().isEmpty()) {
			return Collections.emptyList();
		}
		return new ArrayList<>(columnAnalyzerOverrideDao.getByQualifiedNames(
				config.getColumnAnalyzerOverrides()).values());
	}

	private List<SynonymSet> loadSynonymSets() {
		if (config == null || config.getSynonymSets() == null
				|| config.getSynonymSets().isEmpty()) {
			return Collections.emptyList();
		}
		return new ArrayList<>(synonymSetDao.getByQualifiedNames(
				config.getSynonymSets()).values());
	}

	private Map<String, TextAnalyzer> collectAndLoadAnalyzers() {
		Set<String> qualifiedNames = new HashSet<>();

		// SCIENTIFIC is always needed for keyword .searchable sub-fields
		qualifiedNames.add(ColumnTypeToOpenSearchMapping.getDefaultAnalyzerQualifiedName(ColumnType.STRING));

		// From column analyzer overrides
		for (ColumnAnalyzerOverride cao : getColumnAnalyzerOverrides()) {
			if (cao.getOverrides() != null) {
				for (ColumnAnalyzerOverrideEntry entry : cao.getOverrides()) {
					if (entry.getIndexAnalyzer() != null) {
						qualifiedNames.add(entry.getIndexAnalyzer());
					}
					if (entry.getSearchAnalyzer() != null) {
						qualifiedNames.add(entry.getSearchAnalyzer());
					}
				}
			}
		}

		// From config default
		if (config != null && config.getDefaultAnalyzer() != null) {
			qualifiedNames.add(config.getDefaultAnalyzer());
		}

		// From column type defaults
		for (ColumnModel column : columns) {
			qualifiedNames.add(ColumnTypeToOpenSearchMapping.getDefaultAnalyzerQualifiedName(
					column.getColumnType()));
		}

		return new HashMap<>(textAnalyzerDao.getByQualifiedNames(new ArrayList<>(qualifiedNames)));
	}
}
