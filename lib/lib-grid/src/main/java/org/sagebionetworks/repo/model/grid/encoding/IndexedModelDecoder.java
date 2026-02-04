package org.sagebionetworks.repo.model.grid.encoding;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import org.sagebionetworks.repo.model.grid.ClockTable;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;
import org.sagebionetworks.util.ValidateArgument;

import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.dataformat.cbor.CBORParser;

/**
 * Decoder for the JSON CRDT Indexed Binary format.
 *
 * <p>
 * The indexed binary format is a CBOR map containing:
 * <ul>
 *   <li>"c" — the clock table (binary encoded)</li>
 *   <li>"r" — the root node ID (binary encoded timestamp)</li>
 *   <li>"&lt;sid&gt;_&lt;seq&gt;" — node entries (binary encoded nodes)</li>
 * </ul>
 * </p>
 * <p>
 * The {@link IndexedModelDecoder#build } method scans the file to retrieve the clock table and root node, and builds an
 * index of nodes grouped by type. The index optimizes reading batches of nodes to insert them into the database,
 * minimizing the number of database round-trips. This index is built by scanning the CBOR file and recording the byte
 * offset and length of each node's binary data, allowing for efficient random access.
 * </p>
 *
 * @see <a href="https://jsonjoy.com/specs/json-crdt/encoding/indexed-encoding">Indexed Encoding</a>
 */
public class IndexedModelDecoder {

	/**
	 * An entry in the index representing a single node.
	 */
	public static class Entry {
		private final IndexedNodeCodecMapper type;
		private final long byteOffset;
		private final int binaryLength;

		public Entry(IndexedNodeCodecMapper type, long byteOffset, int binaryLength) {
			this.type = type;
			this.byteOffset = byteOffset;
			this.binaryLength = binaryLength;
		}

		public IndexedNodeCodecMapper type() {
			return type;
		}

		public long byteOffset() {
			return byteOffset;
		}

		public int binaryLength() {
			return binaryLength;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			Entry entry = (Entry) o;
			return byteOffset == entry.byteOffset && binaryLength == entry.binaryLength
					&& type == entry.type;
		}

		@Override
		public int hashCode() {
			return Objects.hash(type, byteOffset, binaryLength);
		}

		@Override
		public String toString() {
			return "Entry{type=" + type + ", byteOffset=" + byteOffset + ", binaryLength=" + binaryLength + "}";
		}
	}

	private final Map<IndexedNodeCodecMapper, Map<LogicalTimestamp, Entry>> entriesByType;
	private final ClockTable clockTable;
	private final LogicalTimestamp rootNodeId;
	private final int totalNodeCount;

	/**
	 * Private constructor - use {@link #build(Path)} to create a decoder.
	 */
	private IndexedModelDecoder(Map<IndexedNodeCodecMapper, Map<LogicalTimestamp, Entry>> entriesByType, ClockTable clockTable,
								LogicalTimestamp rootNodeId, int totalNodeCount) {
		this.entriesByType = entriesByType;
		this.clockTable = clockTable;
		this.rootNodeId = rootNodeId;
		this.totalNodeCount = totalNodeCount;
	}

	/**
	 * Temporary holder for raw node entry data before clock table is available.
	 */
	private static class RawNodePointer {
		final String fieldName;
		final long byteOffset;
		final int binaryLength;
		final byte firstByte;

		RawNodePointer(String fieldName, long byteOffset, int binaryLength, byte firstByte) {
			this.fieldName = fieldName;
			this.byteOffset = byteOffset;
			this.binaryLength = binaryLength;
			this.firstByte = firstByte;
		}
	}

	/**
	 * Build a decoder by scanning the CBOR file.
	 * This method extracts the ClockTable and rootNodeId from the file, then builds
	 * an index of all nodes grouped by type.
	 *
	 * @param snapshotFile the path to the snapshot CBOR file
	 * @return the built decoder
	 * @throws IOException if an I/O error occurs
	 */
	public static IndexedModelDecoder build(Path snapshotFile) throws IOException {
		ValidateArgument.required(snapshotFile, "snapshotFile");

		Map<IndexedNodeCodecMapper, Map<LogicalTimestamp, Entry>> entriesByType = new EnumMap<>(IndexedNodeCodecMapper.class);
		for (IndexedNodeCodecMapper type : IndexedNodeCodecMapper.values()) {
			entriesByType.put(type, new TreeMap<>());
		}

		ClockTable clockTable = null;
		LogicalTimestamp rootNodeId = null;
		byte[] rootNodeBytes = null; // Store if "r" comes before "c"
		List<RawNodePointer> pendingNodes = new ArrayList<>(); // Buffer node pointers until clockTable is available
		int totalCount = 0;

		try (InputStream in = new BufferedInputStream(new FileInputStream(snapshotFile.toFile()));
			 CBORParser parser = CBORUtils.getCBORFactory().createParser(in)) {

			// Expect start of object
			JsonToken token = parser.nextToken();
			if (token != JsonToken.START_OBJECT) {
				throw new IOException("Expected CBOR map, got: " + token);
			}

			// Scan all fields
			while ((token = parser.nextToken()) != null && token != JsonToken.END_OBJECT) {
				if (token != JsonToken.FIELD_NAME) {
					throw new IOException("Expected field name, got: " + token);
				}

				String fieldName = parser.currentName();
				parser.nextToken();

				if ("c".equals(fieldName)) {
					// Clock table field
					byte[] clockTableBytes = parser.getBinaryValue();
					clockTable = ClockTable.fromBinary(clockTableBytes);
					// Decode rootNodeId if we already have the bytes
					if (rootNodeBytes != null) {
						rootNodeId = clockTable.decodeTimestamp(rootNodeBytes);
						rootNodeBytes = null;
					}
					// Process any pending nodes now that we have the clock table
					for (RawNodePointer raw : pendingNodes) {
						LogicalTimestamp nodeId = clockTable.decodeNodeKey(raw.fieldName);
						IndexedNodeCodecMapper nodeType = IndexedNodeCodecMapper.getByFirstByte(raw.firstByte);
						Entry entry = new Entry(nodeType, raw.byteOffset, raw.binaryLength);
						entriesByType.get(nodeType).put(nodeId, entry);
						totalCount++;
					}
					pendingNodes.clear();
				} else if ("r".equals(fieldName)) {
					// Root node ID field
					byte[] bytes = parser.getBinaryValue();
					if (clockTable != null) {
						rootNodeId = clockTable.decodeTimestamp(bytes);
					} else {
						rootNodeBytes = bytes; // Store for later
					}
				} else {
					// Node entry
					byte[] nodeBytes = parser.getBinaryValue();
					int binaryLength = nodeBytes.length;
					long byteOffset = parser.currentLocation().getByteOffset() - binaryLength;

					if (clockTable != null) {
						// Clock table already available - process immediately
						LogicalTimestamp nodeId = clockTable.decodeNodeKey(fieldName);
						IndexedNodeCodecMapper nodeType = IndexedNodeCodecMapper.getByFirstByte(nodeBytes[0]);
						Entry entry = new Entry(nodeType, byteOffset, binaryLength);
						entriesByType.get(nodeType).put(nodeId, entry);
						totalCount++;
					} else {
						// Buffer until clock table is available
						pendingNodes.add(new RawNodePointer(fieldName, byteOffset, binaryLength, nodeBytes[0]));
					}
				}
			}
		}

		if (clockTable == null) {
			throw new IOException("Clock table ('c') not found in snapshot");
		}
		if (rootNodeId == null) {
			throw new IOException("Root node ID ('r') not found in snapshot");
		}

		return new IndexedModelDecoder(entriesByType, clockTable, rootNodeId, totalCount);
	}

	/**
	 * Get the clock table extracted from the snapshot file.
	 *
	 * @return the clock table
	 */
	public ClockTable getClockTable() {
		return clockTable;
	}

	/**
	 * Get the root node ID extracted from the snapshot file.
	 *
	 * @return the root node ID
	 */
	public LogicalTimestamp getRootNodeId() {
		return rootNodeId;
	}

	/**
	 * Get all entries for a specific node type.
	 *
	 * @param type the node type
	 * @return an unmodifiable list of entries for that type, never null
	 */
	public Map<LogicalTimestamp, Entry> getEntriesForType(IndexedNodeCodecMapper type) {
		ValidateArgument.required(type, "type");
		return Collections.unmodifiableMap(entriesByType.getOrDefault(type, Collections.emptyMap()));
	}

	/**
	 * Get the total number of nodes in the index.
	 *
	 * @return the total node count
	 */
	public int getTotalNodeCount() {
		return totalNodeCount;
	}

	/**
	 * Get the count of nodes for a specific type.
	 *
	 * @param type the node type
	 * @return the count of nodes of that type
	 */
	public int getCountForType(IndexedNodeCodecMapper type) {
		ValidateArgument.required(type, "type");
		return entriesByType.getOrDefault(type, Collections.emptyMap()).size();
	}
}
