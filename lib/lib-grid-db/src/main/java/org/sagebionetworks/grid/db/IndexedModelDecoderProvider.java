package org.sagebionetworks.grid.db;

import java.io.IOException;
import java.nio.file.Path;

import org.sagebionetworks.repo.model.grid.encoding.IndexedModelDecoder;

/**
 * Provider for creating {@link IndexedModelDecoder} instances.
 * This abstraction enables unit testing by allowing the decoder creation to be mocked.
 */
@FunctionalInterface
public interface IndexedModelDecoderProvider {

	/**
	 * Build an IndexedModelDecoder from a snapshot file.
	 *
	 * @param snapshotFile the path to the snapshot CBOR file
	 * @return the decoder with the index built
	 * @throws IOException if there is an error reading the file
	 */
	IndexedModelDecoder build(Path snapshotFile) throws IOException;
}
