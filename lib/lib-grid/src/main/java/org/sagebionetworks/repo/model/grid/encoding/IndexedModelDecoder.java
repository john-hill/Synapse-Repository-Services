package org.sagebionetworks.repo.model.grid.encoding;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Supplier;

import org.sagebionetworks.repo.model.grid.ClockTable;
import org.sagebionetworks.repo.model.grid.node.Node;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;
import org.sagebionetworks.util.ValidateArgument;

import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.dataformat.cbor.CBORParser;

/**
 * Streaming decoder for indexed model format.
 *
 * <p>
 * The indexed model format is a CBOR map containing:
 * <ul>
 *   <li>"c" — the clock table (binary encoded)</li>
 *   <li>"r" — the root node ID (binary encoded timestamp)</li>
 *   <li>"&lt;sid&gt;_&lt;seq&gt;" — node entries (binary encoded nodes)</li>
 * </ul>
 * </p>
 * <p>
 * This decoder uses a two-pass approach to support arbitrary field ordering without buffering:
 * <ol>
 *   <li>Scans the entire stream to extract the clock table ("c") and root node ID ("r"),
 *   skipping over node entries.</li>
 *   <li>Streams nodes one at a time, skipping the "c" and "r" fields.</li>
 * </ol>
 * </p>
 * <p>
 * This approach enables true streaming decode for large files  without loading the entire model into memory.
 * </p>
 * Usage:
 * <pre>
 * Supplier&lt;InputStream&gt; streamProvider = () -&gt; new FileInputStream("model.cbor");
 * try (IndexedModelDecoder decoder = new IndexedModelDecoder(streamProvider)) {
 *     ClockTable clockTable = decoder.getClockTable();
 *     LogicalTimestamp rootNodeId = decoder.getRootNodeId();
 *
 *     for (DecodedNode node : decoder) {
 *         // Process node
 *     }
 * }
 * </pre>
 *
 * @see <a href="https://jsonjoy.com/specs/json-crdt/encoding/indexed-encoding">Indexed Encoding</a>
 */
public class IndexedModelDecoder implements Closeable, Iterable<Node> {

	private final Supplier<? extends InputStream> streamProvider;
	private final NodeCodec nodeDecoder;
	private final ClockTable clockTable;
	private final LogicalTimestamp rootNodeId;

	private CBORParser parser;
	private boolean parserInitialized = false;
	private boolean closed = false;

	/**
	 * Create a new IndexedModelDecoder.
	 *
	 * @param streamProvider a supplier that provides a fresh InputStream for each call.
	 *                       The supplier will be called twice: once to scan for metadata,
	 *                       and once to stream nodes.
	 */
	public IndexedModelDecoder(Supplier<? extends InputStream> streamProvider) {
		ValidateArgument.required(streamProvider, "streamProvider");

		this.streamProvider = streamProvider;
		this.nodeDecoder = new IndexedNodeCodec();

		// Pass 1: Scan for metadata (clock table and root node ID)
		Metadata metadata = scanForMetadata();
		this.clockTable = metadata.clockTable;
		this.rootNodeId = metadata.rootNodeId;
	}

	/**
	 * Metadata extracted during the first pass. The metadata is required to decode nodes.
	 */
	private static class Metadata {
		final ClockTable clockTable;
		final LogicalTimestamp rootNodeId;

		Metadata(ClockTable clockTable, LogicalTimestamp rootNodeId) {
			this.clockTable = clockTable;
			this.rootNodeId = rootNodeId;
		}
	}

	/**
	 * Pass 1: Scan the stream to extract the clock table and root node ID.
	 * Skips over all node entries without decoding them.
	 *
	 * @return the extracted metadata
	 * @throws UncheckedIOException if an I/O error occurs
	 */
	private Metadata scanForMetadata() {
		try (InputStream in = streamProvider.get();
			 CBORParser scanParser = CBORUtils.getCBORFactory().createParser(in)) {

			ClockTable clockTable = null;
			LogicalTimestamp rootNodeId = null;
			byte[] rootNodeBytes = null; // Store temporarily if "r" comes before "c"

			// Expect start of object
			JsonToken token = scanParser.nextToken();
			if (token != JsonToken.START_OBJECT) {
				throw new IOException("Expected CBOR map, got: " + token);
			}

			// Scan all fields
			while ((token = scanParser.nextToken()) != null && token != JsonToken.END_OBJECT) {
				if (token != JsonToken.FIELD_NAME) {
					throw new IOException("Expected field name, got: " + token);
				}

				String fieldName = scanParser.currentName();
				scanParser.nextToken(); // Move to value

				if ("c".equals(fieldName)) {
					byte[] clockTableBytes = scanParser.getBinaryValue();
					clockTable = ClockTable.fromBinary(clockTableBytes);

					// If we already have root node bytes, decode them now
					if (rootNodeBytes != null) {
						rootNodeId = clockTable.decodeTimestamp(rootNodeBytes);
						rootNodeBytes = null;
					}
				} else if ("r".equals(fieldName)) {
					byte[] bytes = scanParser.getBinaryValue();
					if (clockTable != null) {
						rootNodeId = clockTable.decodeTimestamp(bytes);
					} else {
						// Store for later when clock table is available
						rootNodeBytes = bytes;
					}
				} else {
					// Node entry - skip the binary value
					scanParser.skipChildren();
				}
			}

			if (clockTable == null) {
				throw new RuntimeException("Clock table ('c') not found in model");
			}
			if (rootNodeId == null) {
				throw new RuntimeException("Root node ID ('r') not found in model");
			}

			return new Metadata(clockTable, rootNodeId);

		} catch (IOException e) {
			throw new RuntimeException("Failed to scan model metadata", e);
		}
	}

	/**
	 * Initialize the pass 2 parser for streaming nodes.
	 */
	private void ensureParserInitialized() throws IOException {
		if (parserInitialized) {
			return;
		}

		// Open pass 2 stream
		InputStream in = streamProvider.get();
		this.parser = CBORUtils.getCBORFactory().createParser(in);

		// Skip to the first node entry
		JsonToken token = parser.nextToken();
		if (token != JsonToken.START_OBJECT) {
			throw new IOException("Expected CBOR map, got: " + token);
		}

		parserInitialized = true;
	}

	/**
	 * Get the clock table for this model.
	 *
	 * @return the clock table
	 */
	public ClockTable getClockTable() {
		return clockTable;
	}

	/**
	 * Get the root node ID for this model.
	 *
	 * @return the root node ID
	 */
	public LogicalTimestamp getRootNodeId() {
		return rootNodeId;
	}

	/**
	 * Read the next node from the model.
	 *
	 * @return the next decoded node, or null if no more nodes
	 * @throws IOException if an I/O error occurs
	 */
	public Node readNode() throws IOException {
		ensureParserInitialized();

		while (true) {
			JsonToken token = parser.nextToken();
			if (token == null || token == JsonToken.END_OBJECT) {
				return null;
			}

			if (token != JsonToken.FIELD_NAME) {
				throw new IOException("Expected field name, got: " + token);
			}

			String fieldName = parser.currentName();
			parser.nextToken(); // Move to value

			// Skip "c" and "r" fields (already processed in pass 1)
			if ("c".equals(fieldName) || "r".equals(fieldName)) {
				parser.skipChildren();
				continue;
			}

			// Decode the node key to get the node ID
			LogicalTimestamp nodeId = this.clockTable.decodeNodeKey(fieldName);

			// Read the node binary
			byte[] nodeBytes = parser.getBinaryValue();

			// Decode the node
			ByteArrayInputStream nodeIn = new ByteArrayInputStream(nodeBytes);
			return nodeDecoder.decode(nodeId, clockTable, nodeIn);
		}
	}

	/**
	 * Returns an iterator over the nodes in this model.
	 * Note: This iterator wraps IOExceptions in RuntimeExceptions.
	 *
	 * @return an iterator over decoded nodes
	 */
	@Override
	public Iterator<Node> iterator() {
		try {
			ensureParserInitialized();
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to initialize parser for node streaming", e);
		}

		return new Iterator<>() {
            private Node next = null;
            private boolean done = false;

            @Override
            public boolean hasNext() {
                if (done) {
                    return false;
                }
                if (next != null) {
                    return true;
                }
                try {
                    next = readNode();
                    if (next == null) {
                        done = true;
                        return false;
                    }
                    return true;
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to read node", e);
                }
            }

            @Override
            public Node next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                Node result = next;
                next = null;
                return result;
            }
        };
	}

	/**
	 * Close the decoder.
	 *
	 * @throws IOException if an I/O error occurs
	 */
	@Override
	public void close() throws IOException {
		if (!closed) {
			if (parser != null) {
				parser.close();
			}
			closed = true;
		}
	}

	/**
	 * Convenience method to decode a model and collect all nodes.
	 * Note: This loads all nodes into memory, defeating the streaming purpose.
	 * Use the iterator or forEachNode for streaming.
	 *
	 * @param streamProvider a supplier that provides a fresh InputStream for each call
	 * @return a result containing the clock table, root node ID, and all nodes
	 * @throws UncheckedIOException if an I/O error occurs
	 */
	public static DecodedModel decodeModel(Supplier<InputStream> streamProvider) {
		try (IndexedModelDecoder decoder = new IndexedModelDecoder(streamProvider)) {
			ClockTable clockTable = decoder.getClockTable();
			LogicalTimestamp rootNodeId = decoder.getRootNodeId();
			List<Node> nodes = new ArrayList<>();
			decoder.iterator().forEachRemaining(nodes::add);
			return new DecodedModel(clockTable, rootNodeId, nodes);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to decode model", e);
		}
	}

	/**
	 * Result of decoding a complete model.
	 */
	public static class DecodedModel {
		private final ClockTable clockTable;
		private final LogicalTimestamp rootNodeId;
		private final List<Node> nodes;

		public DecodedModel(ClockTable clockTable, LogicalTimestamp rootNodeId, List<Node> nodes) {
			this.clockTable = clockTable;
			this.rootNodeId = rootNodeId;
			this.nodes = nodes;
		}

		public ClockTable getClockTable() {
			return clockTable;
		}

		public LogicalTimestamp getRootNodeId() {
			return rootNodeId;
		}

		public List<Node> getNodes() {
			return nodes;
		}
	}
}
