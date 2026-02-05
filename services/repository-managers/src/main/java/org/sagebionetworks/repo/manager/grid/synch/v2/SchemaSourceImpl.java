package org.sagebionetworks.repo.manager.grid.synch.v2;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.sagebionetworks.repo.manager.grid.synch.SourceHandler;

public class SchemaSourceImpl implements SchemaSource {

	private final SourceHandler handler;
	private final List<ColumnItem> schema;

	public SchemaSourceImpl(SourceHandler handler) {
		this.handler = handler;
		this.schema = handler.getCurrentSourceSchema().stream().map(n -> new ColumnItem(n, false))
				.collect(Collectors.toList());
	}

	@Override
	public String getKey(ColumnItem item) {
		return item.getColumnName();
	}

	@Override
	public Optional<ColumnItem> consume(String key) {
		Optional<ColumnItem> item = schema.stream().filter(i -> i.getColumnName().equals(key)).findFirst();
		if (item.isPresent()) {
			schema.remove(item.get());
		}
		return item;
	}

	@Override
	public Stream<ColumnItem> streamRemaining() {
		return schema.stream();
	}

	@Override
	public void addItem(ColumnItem toAdd) {
		handler.addColumnToSource(toAdd.getColumnName());
	}

	@Override
	public void removeItem(ColumnItem toRemove) {
		handler.deleteColumn(toRemove.getColumnName());
	}

}
