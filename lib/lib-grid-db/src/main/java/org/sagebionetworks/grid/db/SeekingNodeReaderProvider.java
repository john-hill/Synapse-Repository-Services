package org.sagebionetworks.grid.db;

import java.io.IOException;
import java.nio.file.Path;

import org.sagebionetworks.repo.model.grid.ClockTable;
import org.sagebionetworks.repo.model.grid.encoding.SeekingNodeReader;

/**
 * Provider for creating {@link SeekingNodeReader} instances.
 * This abstraction enables unit testing by allowing the reader creation to be mocked.
 */
@FunctionalInterface
public interface SeekingNodeReaderProvider {

	/**
	 * Create a SeekingNodeReader for reading nodes from a snapshot file.
	 *
	 * @param snapshotFile the path to the snapshot CBOR file
	 * @param clockTable the clock table from the snapshot
	 * @return a new SeekingNodeReader
	 * @throws IOException if there is an error opening the file
	 */
	SeekingNodeReader create(Path snapshotFile, ClockTable clockTable) throws IOException;
}
