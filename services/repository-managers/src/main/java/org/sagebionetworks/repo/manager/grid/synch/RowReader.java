package org.sagebionetworks.repo.manager.grid.synch;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.sagebionetworks.util.ValidateArgument;

public class RowReader implements AutoCloseable {

	private final Map<String, DiskPointer> diskPointerMap;
	private final RandomAccessFile raf;

	public RowReader(List<DiskPointer> diskPointers, RandomAccessFile raf) {
		ValidateArgument.required(raf, "RandomAccessFile");
		ValidateArgument.required(diskPointers, "DiskPointers");
		this.diskPointerMap = diskPointers.stream()
				.collect(Collectors.toMap(DiskPointer::getKey, pointer -> pointer));
		this.raf = raf;
	}

	public Optional<RowHeader> removeRow(String key) {
		DiskPointer diskPointer = diskPointerMap.remove(key);
		if (diskPointer == null) {
			return Optional.empty();
		}
		return Optional.of(createRowHeader(diskPointer));
	}
	
	public Iterator<RowHeader> remainingRows() {
	    return diskPointerMap.values().stream()
	        .map(this::createRowHeader)
	        .iterator();
	}

	private RowHeader createRowHeader(DiskPointer diskPointer) {
		return new RowHeader() {
			@Override
			public byte[] getHash() {
				return diskPointer.getHash();
			}

			@Override
			public SynchRow fetchRow() throws IOException {
				raf.seek(diskPointer.getOffset());
				byte[] buffer = new byte[diskPointer.getLength()];
				raf.readFully(buffer);
				return new SynchRow(buffer, diskPointer.getKey());
			}
		};
	}

	@Override
	public void close() throws IOException {
		raf.close();
	}
}
