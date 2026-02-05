package org.sagebionetworks.repo.manager.grid.synch;

import java.io.IOException;
import java.io.OutputStream;

public class RowWriter implements AutoCloseable {

	private final OutputStream out;
	private long position;

	public RowWriter(OutputStream out) {
		this.out = out;
	}

	public DiskPointer nextRow(SynchRow row) {
		try {
			long startOffset = position;
			byte[] bytes = row.getBytes();
			out.write(bytes);
			position += bytes.length;
			return new DiskPointer(row.getKey(), row.getHash(), startOffset, bytes.length);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public void close() throws IOException {
		out.close();
	}

}
