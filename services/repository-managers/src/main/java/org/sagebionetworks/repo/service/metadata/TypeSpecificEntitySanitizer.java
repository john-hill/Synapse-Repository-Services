package org.sagebionetworks.repo.service.metadata;

import org.sagebionetworks.repo.model.Entity;

public interface TypeSpecificEntitySanitizer<T extends Entity> extends EntityProvider<T> {

	/**
	 * Sanitize an entity before it is created or updated. This step is allowed to
	 * mutate the entity to strip or normalize server-controlled fields that a client
     * must not be able to set directly. It runs after validation and immediately
     * before the entity is persisted.
	 *
	 * @param entity the entity to sanitize (mutated in place)
	 * @param event  the create/update event context
	 */
	void sanitizeEntity(T entity, EntityEvent event);

}
