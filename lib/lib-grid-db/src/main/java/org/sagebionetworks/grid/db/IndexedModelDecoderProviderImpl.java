package org.sagebionetworks.grid.db;

import java.io.IOException;
import java.nio.file.Path;

import org.sagebionetworks.repo.model.grid.encoding.IndexedModelDecoder;
import org.springframework.stereotype.Component;

@Component
public class IndexedModelDecoderProviderImpl implements IndexedModelDecoderProvider {

	@Override
	public IndexedModelDecoder build(Path snapshotFile) throws IOException {
		return IndexedModelDecoder.build(snapshotFile);
	}
}
