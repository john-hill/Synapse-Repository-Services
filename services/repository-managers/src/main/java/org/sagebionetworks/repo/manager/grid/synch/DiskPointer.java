package org.sagebionetworks.repo.manager.grid.synch;

import java.util.Arrays;
import java.util.Objects;

public class DiskPointer {
	
	private final String key;
	private final byte[] hash;
	private final long offset;
	private final int length;
	
	public DiskPointer(String key, byte[] hash, long offset, int length) {
		super();
		this.key = key;
		this.hash = hash;
		this.offset = offset;
		this.length = length;
	}

	public String getKey() {
		return key;
	}

	public byte[] getHash() {
		return hash;
	}

	public long getOffset() {
		return offset;
	}

	public int getLength() {
		return length;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + Arrays.hashCode(hash);
		result = prime * result + Objects.hash(key, length, offset);
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		DiskPointer other = (DiskPointer) obj;
		return Arrays.equals(hash, other.hash) && Objects.equals(key, other.key) && length == other.length
				&& offset == other.offset;
	}
	
	

}
