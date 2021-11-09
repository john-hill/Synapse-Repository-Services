package org.sagebionetworks.table.query.model;

import lombok.Builder;
import lombok.Data;
import lombok.NonNull;

@Data
@Builder(toBuilder = true)
public class SampleBuilder {

	private final @NonNull String firstName;
	private final @NonNull String lastName;
	private final SubBuilder subBuilder;
}
