package org.sagebionetworks.repo.service.metadata;

import org.sagebionetworks.repo.manager.search.SearchIndexValidator;
import org.sagebionetworks.repo.model.AuthorizationUtils;
import org.sagebionetworks.repo.model.DatastoreException;
import org.sagebionetworks.repo.model.InvalidModelException;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.search.table.SearchIndex;
import org.sagebionetworks.repo.web.NotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Metadata provider for SearchIndex entities. Handles validation during entity
 * creation and update via the MetadataProviderFactory pattern.
 * <p>
 * The entity is fully mutable (definingSQL, searchConfigurationId, name can all
 * change on update). However, AOSS indexes follow build-once semantics: the index
 * is only built on CREATE (or on UPDATE if no index exists yet, for migration
 * backfill). Metadata changes are persisted to NODE_REVISION and will be picked
 * up the next time the index is built during a stack migration.
 */
@Service
public class SearchIndexMetadataProvider implements
		EntityValidator<SearchIndex>,
		TypeSpecificDefiningSqlProvider<SearchIndex> {

	private final SearchIndexValidator searchIndexValidator;

	@Autowired
	public SearchIndexMetadataProvider(SearchIndexValidator searchIndexValidator) {
		this.searchIndexValidator = searchIndexValidator;
	}

	@Override
	public void validateEntity(SearchIndex entity, EntityEvent event)
			throws InvalidModelException, NotFoundException, DatastoreException, UnauthorizedException {
		// Pilot gate: only Sage employees or admins can create/update SearchIndex entities.
		// This will be removed when SearchIndex creation is released to the general user base.
		UserInfo user = event.getUserInfo();
		if (!AuthorizationUtils.isSageEmployeeOrAdmin(user)) {
			throw new UnauthorizedException("Only Sage Bionetworks employees or admins can manage search index entities.");
		}
		// Validate the definingSQL on both CREATE and UPDATE
		searchIndexValidator.validateDefiningSQL(entity.getDefiningSQL());
	}

	@Override
	public void validateDefiningSql(String definingSql) {
		searchIndexValidator.validateDefiningSQL(definingSql);
	}
}
