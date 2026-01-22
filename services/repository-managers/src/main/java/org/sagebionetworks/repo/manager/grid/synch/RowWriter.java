package org.sagebionetworks.repo.manager.grid.synch;

import java.io.IOException;
import java.io.OutputStream;

public class RowWriter implements AutoCloseable {

	private final CountingOutputStream out;

	public RowWriter(OutputStream out) {
		this.out = new CountingOutputStream(out);
	}

	public DiskPointer nextRow(SynchRow row) {
		try {
			long startOffset = out.getCount();

			out.write(row.getBytes());

			long length = out.getCount() - startOffset;
			if (length > Integer.MAX_VALUE) {
				throw new IllegalStateException("Row size exceeds maximum allowed size: " + length);
			}
			return new DiskPointer(row.getKey(), row.getHash(), startOffset, (int) length);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public void close() throws IOException {
		this.out.close();
	}

	private static class CountingOutputStream extends OutputStream {
		private final OutputStream delegate;
		private long count = 0;

		public CountingOutputStream(OutputStream delegate) {
			this.delegate = delegate;
		}

		@Override
		public void write(int b) throws IOException {
			delegate.write(b);
			count++;
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			delegate.write(b, off, len);
			count += len;
		}

		public long getCount() {
			return count;
		}
	}
}
