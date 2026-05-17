package org.sagebionetworks.repo.model.dbo.search;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.sagebionetworks.repo.model.search.table.ColumnAnalyzerOverride;

public interface ColumnAnalyzerOverrideDao {
	ColumnAnalyzerOverride create(Long createdBy, ColumnAnalyzerOverride override);
	Optional<ColumnAnalyzerOverride> get(String id);
	ColumnAnalyzerOverride update(Long modifiedBy, ColumnAnalyzerOverride override);
	void delete(String id);
	List<ColumnAnalyzerOverride> list(String organizationName, long limit, long offset);
	List<ColumnAnalyzerOverride> listAll(long limit, long offset);
	Optional<ColumnAnalyzerOverride> getByOrganizationAndName(String organizationName, String name);
	List<String> findNonExistentNames(List<String> qualifiedNames);

	/**
	 * Batch lookup column analyzer overrides by their qualified names ({orgName}-{name}).
	 * @return Map of qualified name to ColumnAnalyzerOverride for all found entries
	 */
	Map<String, ColumnAnalyzerOverride> getByQualifiedNames(List<String> qualifiedNames);

	void truncateAll();
}
