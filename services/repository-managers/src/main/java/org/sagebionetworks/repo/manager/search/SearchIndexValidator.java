package org.sagebionetworks.repo.manager.search;

import java.util.List;

import org.sagebionetworks.repo.model.entity.IdAndVersion;
import org.sagebionetworks.table.cluster.utils.TableModelUtils;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.stereotype.Service;

@Service
public class SearchIndexValidator {

	/**
	 * Validates that the given definingSQL references exactly one source entity.
	 * Multi-entity JOINs are not supported for search indexes.
	 *
	 * @param definingSQL The SQL defining the search index
	 * @throws IllegalArgumentException if the SQL is blank or references more than one entity
	 */
	public void validateDefiningSQL(String definingSQL) {
		ValidateArgument.requiredNotBlank(definingSQL, "definingSQL");
		List<IdAndVersion> sourceTableIds = TableModelUtils.getSourceTableIds(definingSQL);
		if (sourceTableIds.size() != 1) {
			throw new IllegalArgumentException(
				"definingSQL must reference exactly one entity. Multi-entity JOINs are not supported. Found: " + sourceTableIds);
		}
	}
}
