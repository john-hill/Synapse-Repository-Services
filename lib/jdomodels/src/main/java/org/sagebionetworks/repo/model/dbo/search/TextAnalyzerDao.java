package org.sagebionetworks.repo.model.dbo.search;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.sagebionetworks.repo.model.search.table.TextAnalyzer;

public interface TextAnalyzerDao {

	TextAnalyzer create(TextAnalyzer analyzer, Long userId);

	Optional<TextAnalyzer> get(Long id);

	TextAnalyzer update(TextAnalyzer analyzer, Long userId);

	void delete(Long id);

	List<TextAnalyzer> listByOrganization(String organizationName, long limit, long offset);

	List<TextAnalyzer> listAll(long limit, long offset);

	boolean exists(Long id);

	Optional<TextAnalyzer> getByOrganizationAndName(String organizationName, String name);

	List<String> findNonExistentNames(List<String> qualifiedNames);

	/**
	 * Batch lookup analyzers by their qualified names ({orgName}-{name}).
	 * @return Map of qualified name to TextAnalyzer for all found entries
	 */
	Map<String, TextAnalyzer> getByQualifiedNames(List<String> qualifiedNames);

	void createOrUpdateSystemAnalyzerForBootstrapOnly(Long id, TextAnalyzer analyzer, String organizationName, Long userId);

	void truncateAll();
}
