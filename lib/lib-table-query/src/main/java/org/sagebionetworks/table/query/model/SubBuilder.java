package org.sagebionetworks.table.query.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder(toBuilder = true)
public class SubBuilder {

	private final Long aLong;
	private final String aString;
}
