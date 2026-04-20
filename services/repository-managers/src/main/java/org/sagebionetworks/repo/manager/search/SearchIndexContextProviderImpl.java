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
 * Lazy-loading implementation of {@link SearchIndexContextProvider}.
 * Columns and defaultAnalyzer are set eagerly (no DB call needed).
 * Analyzers, overrides, and synonym sets are loaded on first access and cached.
 */
public class SearchIndexContextProviderImpl implements SearchIndexContextProvider {

	private final SearchConfiguration config;
	private final List<ColumnModel> columns;
	private final SynonymSetDao synonymSetDao;
	private final ColumnAnalyzerOverrideDao columnAnalyzerOverrideDao;
	private final TextAnalyzerDao textAnalyzerDao;

	private List<ColumnAnalyzerOverride> columnAnalyzerOverrides;
	private Map<String, TextAnalyzer> analyzers;
	private List<SynonymSet> synonymSets;

	public SearchIndexContextProviderImpl(SearchConfiguration config, List<ColumnModel> columns,
			SynonymSetDao synonymSetDao, ColumnAnalyzerOverrideDao columnAnalyzerOverrideDao,
			TextAnalyzerDao textAnalyzerDao) {
		this.config = config;
		this.columns = columns;
		this.synonymSetDao = synonymSetDao;
		this.columnAnalyzerOverrideDao = columnAnalyzerOverrideDao;
		this.textAnalyzerDao = textAnalyzerDao;
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
		if (config == null || config.getSynonymSets() == null || config.getSynonymSets().isEmpty()) {
			return Collections.emptyList();
		}
		return new ArrayList<>(synonymSetDao.getByQualifiedNames(config.getSynonymSets()).values());
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
