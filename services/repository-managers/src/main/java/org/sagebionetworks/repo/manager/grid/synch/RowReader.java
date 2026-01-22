package org.sagebionetworks.repo.manager.grid.synch;

import java.io.IOException;
import java.io.RandomAccessFile;
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
		this.diskPointerMap = diskPointers.stream().collect(Collectors.toMap(DiskPointer::getKey, pointer -> pointer));
		this.raf = raf;
	}

	public Optional<SynchRow> lookupRow(String key) throws IOException {
		DiskPointer diskPointer = diskPointerMap.get(key);
		if (diskPointer == null) {
			return Optional.empty();
		}
		raf.seek(diskPointer.getOffset());
		byte[] buffer = new byte[diskPointer.getLength()];
		raf.readFully(buffer);
		return Optional.of(new SynchRow(buffer, key));
	}

	@Override
	public void close() throws IOException {
		raf.close();
	}

}
