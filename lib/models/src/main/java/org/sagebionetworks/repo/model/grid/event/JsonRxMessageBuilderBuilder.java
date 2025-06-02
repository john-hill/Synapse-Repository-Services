package org.sagebionetworks.repo.model.grid.event;

import java.util.Optional;

public interface JsonRxMessageBuilderBuilder<M, B> {

	JsonRxMessageType type();

	Optional<String> method();
	
	Optional<Class<? extends B>> bodyType();
	
	JsonRxMessageBuilder<M, B> builder();
}
