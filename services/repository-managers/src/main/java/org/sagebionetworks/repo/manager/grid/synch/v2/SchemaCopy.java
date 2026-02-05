package org.sagebionetworks.repo.manager.grid.synch.v2;

import java.util.List;

import org.sagebionetworks.repo.manager.grid.internal.replica.model.Column;

public interface SchemaCopy extends Copy<ColumnItem> {

	List<Column> getFinalSchema();
}
