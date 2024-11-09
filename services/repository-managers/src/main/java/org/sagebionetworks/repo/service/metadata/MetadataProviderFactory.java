package org.sagebionetworks.repo.service.metadata;

import java.util.Optional;

import org.sagebionetworks.repo.model.Entity;
import org.sagebionetworks.repo.model.EntityType;

public interface MetadataProviderFactory {
	
	Optional<EntityProvider<? extends Entity>> getMetadataProvider(EntityType type);

}
