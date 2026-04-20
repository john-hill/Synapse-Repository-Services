package org.sagebionetworks.repo.manager.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * Columns, defaultAnalyzer, overrides, and synonym sets are set eagerly.
 * Analyzers are loaded lazily on first access via {@link TextAnalyzerDao}.
 */
public class SearchIndexContextProviderImpl implements SearchIndexContextProvider {

	private final SearchConfiguration config;
	private final List<ColumnModel> columns;
	private final List<ColumnAnalyzerOverride> columnAnalyzerOverrides;
	private final List<SynonymSet> synonymSets;
	private final TextAnalyzerDao textAnalyzerDao;

	private Map<String, TextAnalyzer> analyzers;

	public SearchIndexContextProviderImpl(SearchConfiguration config, List<ColumnModel> columns,
			List<ColumnAnalyzerOverride> columnAnalyzerOverrides, List<SynonymSet> synonymSets,
			TextAnalyzerDao textAnalyzerDao) {
		this.config = config;
		this.columns = columns;
		this.columnAnalyzerOverrides = columnAnalyzerOverrides != null ? columnAnalyzerOverrides : Collections.emptyList();
		this.synonymSets = synonymSets != null ? synonymSets : Collections.emptyList();
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
		return synonymSets;
	}

	private Map<String, TextAnalyzer> collectAndLoadAnalyzers() {
		Set<String> qualifiedNames = new HashSet<>();

		// SCIENTIFIC is always needed for keyword .searchable sub-fields
		qualifiedNames.add(ColumnTypeToOpenSearchMapping.getDefaultAnalyzerQualifiedName(ColumnType.STRING));

		// From column analyzer overrides
		for (ColumnAnalyzerOverride cao : columnAnalyzerOverrides) {
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
