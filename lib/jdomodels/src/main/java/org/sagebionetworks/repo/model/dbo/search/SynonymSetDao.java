package org.sagebionetworks.repo.model.dbo.search;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.sagebionetworks.repo.model.search.table.SynonymSet;

public interface SynonymSetDao {
	SynonymSet create(Long createdBy, SynonymSet synonymSet);
	Optional<SynonymSet> get(String id);
	SynonymSet update(Long modifiedBy, SynonymSet synonymSet);
	void delete(String id);
	List<SynonymSet> list(String organizationName, long limit, long offset);
	List<SynonymSet> listAll(long limit, long offset);
	Optional<SynonymSet> getByOrganizationAndName(String organizationName, String name);

	/**
	 * Given a list of qualified names ({orgName}-{name}), return the subset that does not
	 * exist in the database. Used at create/update of a SearchConfiguration to validate
	 * every referenced SynonymSet qname resolves before saving.
	 */
	List<String> findNonExistentNames(List<String> qualifiedNames);

	/**
	 * Batch lookup synonym sets by their qualified names ({orgName}-{name}).
	 * @return Map of qualified name to SynonymSet for all found entries
	 */
	Map<String, SynonymSet> getByQualifiedNames(List<String> qualifiedNames);

	void truncateAll();
}
