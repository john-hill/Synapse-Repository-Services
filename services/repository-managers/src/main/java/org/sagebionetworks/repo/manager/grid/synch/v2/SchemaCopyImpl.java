package org.sagebionetworks.repo.manager.grid.synch.v2;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.sagebionetworks.repo.manager.grid.internal.replica.model.Column;
import org.sagebionetworks.repo.manager.grid.synch.CopyReader;

public class SchemaCopyImpl implements SchemaCopy {

	private final CopyReader copyReader;
	private final List<ColumnItem> schema;
	private final List<Column> finalSchema;

	public SchemaCopyImpl(CopyReader copyReader) {
		super();
		this.copyReader = copyReader;
		long internalReplica = copyReader.getConnectionInfo().getReplicaId();
		this.schema = copyReader.getHeader().getOrderedColumns().stream().map(c -> {
			boolean wasChangedByUser = !c.getColumnOrderNodeId().getRep().equals(internalReplica);
			return new ColumnItem(c.getName(), wasChangedByUser);
		}).collect(Collectors.toList());
		this.finalSchema = new ArrayList<>(copyReader.getHeader().getOrderedColumns());
	}

	@Override
	public Stream<ColumnItem> streamItems() {
		return schema.stream();
	}

	@Override
	public boolean wasDeletedByUser(String key) {
		return false;
	}

	@Override
	public void removeItem(ColumnItem item) {
		schema.remove(item);
	}

	@Override
	public void addItem(ColumnItem item) {

	}

	@Override
	public List<Column> getFinalSchema() {
		return finalSchema;
	}

}
