package org.sagebionetworks.repo.model.grid.encoding;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;

import org.sagebionetworks.repo.model.grid.ClockTable;
import org.sagebionetworks.repo.model.grid.node.Node;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;
import org.sagebionetworks.util.ValidateArgument;

import com.fasterxml.jackson.dataformat.cbor.CBORGenerator;

/**
 * Encoder for the JSON CRDT indexed model format.
 * <p>
 * The indexed model format is a CBOR map containing:
 * <ul>
 *   <li>"c" — the clock table (binary encoded)</li>
 *   <li>"r" — the root node ID (binary encoded timestamp)</li>
 *   <li>"&lt;sid&gt;_&lt;seq&gt;" — node entries (binary encoded nodes)</li>
 * </ul>
 * </p>
 * This encoder streams nodes without buffering, completing an indefinite-length CBOR map on close.
 *
 * Usage:
 * <pre>
 * try (IndexedModelEncoder encoder = new IndexedModelEncoder(outputStream, clockTable, rootNodeId)) {
 *     for (Node node : nodes) {
 *         encoder.writeNode(node);
 *     }
 *    // Closing the encoder writes the rest of the file.
 * }
 * </pre>
 *
 * @see <a href="https://jsonjoy.com/specs/json-crdt/encoding/indexed-encoding">Indexed Encoding</a>
 */
public class IndexedModelEncoder implements Closeable {
	private final LogicalTimestamp rootNodeId;
	private final NodeCodec nodeCodec;
	private final CBORGenerator generator;

    private ClockTable clockTable;
    private boolean closed = false;

	/**
	 * Create a new IndexedModelEncoder.
	 *
	 * @param out the output stream to write to
	 * @param clockTable the clock table for the model. For the indexed encoder, the initial clock table MUST include
	 *                     all replica IDs in the correct order. However, the clock table does not need to have the
	 *                     latest sequence numbers, as timestamps are encoded using raw sequence numbers. To update the
	 *                     clock table, use {@link #setClockTable(ClockTable)}.
	 * @param rootNodeId the ID of the root node
	 * @throws IOException if an I/O error occurs
	 */
	public IndexedModelEncoder(OutputStream out, ClockTable clockTable, LogicalTimestamp rootNodeId) {
		ValidateArgument.required(out, "out");
		ValidateArgument.required(clockTable, "clockTable");
		ValidateArgument.required(rootNodeId, "rootNodeId");

		this.clockTable = clockTable;
		this.rootNodeId = rootNodeId;
		this.nodeCodec = new IndexedNodeCodec();

		try {
			this.generator = CBORUtils.getCBORFactory().createGenerator(out);
			this.generator.writeStartObject();
		} catch (IOException e) {
			throw new RuntimeException("Failed to create CBOR generator", e);
		}
	}

	/**
	 * Write a node to the model.
	 *
	 * @param node the node to write
	 * @throws IOException if an I/O error occurs
	 * @throws IllegalStateException if the encoder has been closed
	 */
	public void writeNode(Node node) throws IOException {
		if (closed) {
			throw new IllegalStateException("Encoder has been closed");
		}
		ValidateArgument.required(node, "node");
		ValidateArgument.required(node.getId(), "node.id");

		// Generate the node key
		String nodeKey = clockTable.encodeNodeKey(node.getId());

		// Encode the node to binary
		// Use a Piped stream to avoid buffering the entire node in memory
		final PipedInputStream in = new PipedInputStream();
		final PipedOutputStream out = new PipedOutputStream(in);
		int length = nodeCodec.encode(node, clockTable, out);

		// Write the entry to the output file
		writeNodeEntry(nodeKey, in, length);
	}

	private void writeNodeEntry(String key, InputStream byteStream, int length) throws IOException {
		generator.writeFieldName(key);
		generator.writeBinary(byteStream, length);
	}

	/**
	 * Close the encoder, writing the complete CBOR map.
	 * This must be called to produce valid output.
	 *
	 * @throws IOException if an I/O error occurs
	 */
	@Override
	public void close() throws IOException {
		if (!closed) {
			// Write clock table ("c")
			generator.writeFieldName("c");
			generator.writeBinary(clockTable.toBinary());

			// Write root node ID ("r") - uses raw sequence number, not difference encoding
			generator.writeFieldName("r");
			generator.writeBinary(clockTable.encodeTimestamp(rootNodeId));

			generator.writeEndObject();


			closed = true;
			generator.close();
		}
	}

    /**
     * Sets a new clock table for this encoder. The index of each replica in the clock table is used to encode nodes,
     * so the new clock table must have the same replica IDs in the same order as the existing clock table.
     * <p>
     * This method simplifies instantiating a new CRDT document where the replica ID(s) are known beforehand, but the number
     * of nodes in the document is not known. For example, when instantiating a data grid, you may perform a sequence like:
     * </p>
     * <ol>
     * <li>Create an IndexedModelEncoder with a clock table containing only the internal replica ID.</li>
     * <li>Write nodes to the encoder for an unknown number of rows in the grid. The internal replica is the creator of
     * each node.</li>
     * <li>Once all nodes are written, update the clock table such that it properly captures the sequence number of the
     * internal replica.</li>
     * </ol>
     *
     * @param clockTable
     */
	public void setClockTable(ClockTable clockTable) {
		ValidateArgument.required(clockTable, "clockTable");
		if (closed) {
			throw new IllegalStateException("Encoder has been closed");
		}

		// Verify the clock tables have the same replica IDs in the same order
		if (this.clockTable.getClocks().size() != clockTable.getClocks().size()) {
			throw new IllegalArgumentException("New clock table must have the same replica IDs in the same order. The clock table sizes differ.");
		}
		for (int i = 0; i < this.clockTable.getClocks().size(); i++) {
            if (!this.clockTable.getClocks().get(i).getReplicaId()
                    .equals(clockTable.getClocks().get(i).getReplicaId())) {
                throw new IllegalArgumentException("New clock table must have the same replica IDs in the same order");
            }
        }

        this.clockTable = clockTable;
	}
}
