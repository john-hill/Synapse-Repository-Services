package org.sagebionetworks.grid.db;

import java.io.IOException;
import java.nio.file.Path;

import org.sagebionetworks.repo.model.grid.ClockTable;
import org.sagebionetworks.repo.model.grid.encoding.SeekingNodeReader;
import org.springframework.stereotype.Component;

@Component
public class SeekingNodeReaderProviderImpl implements SeekingNodeReaderProvider {

	@Override
	public SeekingNodeReader create(Path snapshotFile, ClockTable clockTable) throws IOException {
		return new SeekingNodeReader(snapshotFile, clockTable);
	}
}
