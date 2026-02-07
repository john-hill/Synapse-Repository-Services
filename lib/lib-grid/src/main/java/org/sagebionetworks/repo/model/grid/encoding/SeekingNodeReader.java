package org.sagebionetworks.repo.model.grid.encoding;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.sagebionetworks.repo.model.grid.ClockTable;
import org.sagebionetworks.repo.model.grid.node.Node;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;
import org.sagebionetworks.util.ValidateArgument;

/**
 * A reader that uses random access to read nodes from a snapshot file at specific byte offsets.
 * This enables efficient type-based batch processing by allowing nodes to be read in any order
 * without sequential scanning.
 *
 * <p>The byte offset recorded by {@link IndexedModelDecoder} points directly to the binary content,
 * so this reader simply seeks to the offset and reads the specified number of bytes.</p>
 */
public class SeekingNodeReader implements Closeable {

	private final RandomAccessFile raf;
	private final ClockTable clockTable;
	private final NodeCodec decoder;

	/**
	 * Create a new SeekingNodeReader.
	 *
	 * @param snapshotFile the path to the snapshot CBOR file
	 * @param clockTable the clock table for decoding nodes
	 * @throws IOException if an I/O error occurs opening the file
	 */
	public SeekingNodeReader(Path snapshotFile, ClockTable clockTable) throws IOException {
		ValidateArgument.required(snapshotFile, "snapshotFile");
		ValidateArgument.required(clockTable, "clockTable");

		this.raf = new RandomAccessFile(snapshotFile.toFile(), "r");
		this.clockTable = clockTable;
		this.decoder = new IndexedNodeCodec();
	}

	/**
	 * Read a single node from the file at the offset specified by the entry.
	 *
	 * @param nodePointer the index entry containing the byte offset and length
	 * @return the decoded node
	 * @throws IOException if an I/O error occurs
	 */
	public Node readNode(LogicalTimestamp nodeId, IndexedModelDecoder.NodePointer nodePointer) throws IOException {
		ValidateArgument.required(nodePointer, "entry");

		// Seek directly to the binary content (offset points to content, not CBOR header)
		raf.seek(nodePointer.byteOffset());

		// Read the binary content
		byte[] nodeBytes = new byte[nodePointer.binaryLength()];
		raf.readFully(nodeBytes);

		// Decode the node
		try (ByteArrayInputStream nodeIn = new ByteArrayInputStream(nodeBytes)) {
			return decoder.decode(nodeId, clockTable, nodeIn);
		}
	}

	/**
	 * Read multiple nodes for efficiency when processing batches.
	 *
	 * @param entries the list of index entries to read
	 * @return the list of decoded nodes in the same order as entries
	 * @throws IOException if an I/O error occurs
	 */
	public List<Node> readNodes(Collection<Map.Entry<LogicalTimestamp, IndexedModelDecoder.NodePointer>> entries) throws IOException {
		ValidateArgument.required(entries, "entries");

		List<Node> nodes = new ArrayList<>(entries.size());
		for (Map.Entry<LogicalTimestamp, IndexedModelDecoder.NodePointer> entry : entries) {
			nodes.add(readNode(entry.getKey(), entry.getValue()));
		}
		return nodes;
	}

	@Override
	public void close() throws IOException {
		raf.close();
	}
}
