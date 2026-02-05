package org.sagebionetworks.repo.manager.grid.synch.v2;

import java.util.Optional;
import java.util.stream.Stream;

public class RowSourceImpl implements RowSource {

	@Override
	public String getKey(RowItem item) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Optional<RowItem> consume(String key) {
		// TODO Auto-generated method stub
		return Optional.empty();
	}

	@Override
	public Stream<RowItem> streamRemaining() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void addItem(RowItem toAdd) {
		// TODO Auto-generated method stub

	}

	@Override
	public void removeItem(RowItem toRemove) {
		// TODO Auto-generated method stub

	}

}
