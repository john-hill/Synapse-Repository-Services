package org.sagebionetworks.repo.model.grid.encoding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sagebionetworks.repo.model.grid.ClockTable;
import org.sagebionetworks.repo.model.grid.node.ArrayNode;
import org.sagebionetworks.repo.model.grid.node.ConstantNode;
import org.sagebionetworks.repo.model.grid.node.Node;
import org.sagebionetworks.repo.model.grid.node.ObjectNode;
import org.sagebionetworks.repo.model.grid.node.RGANode;
import org.sagebionetworks.repo.model.grid.node.ValueNode;
import org.sagebionetworks.repo.model.grid.node.VectorNode;
import org.sagebionetworks.repo.model.grid.patch.ConType;
import org.sagebionetworks.repo.model.grid.patch.ConValue;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;

public class SeekingNodeReaderTest {

	private Path tempFile;
	private List<Node> originalNodes;

	@BeforeEach
	public void setUp() throws IOException {
		tempFile = Files.createTempFile("seeking-reader-test-", ".cbor");
	}

	@AfterEach
	public void tearDown() throws IOException {
		if (tempFile != null) {
			Files.deleteIfExists(tempFile);
		}
	}

	@Test
	public void testReadConstantNode() throws IOException {
		LogicalTimestamp rootId = new LogicalTimestamp().setReplicaId(100L).setSequenceNumber(1L);
		ConstantNode constant = new ConstantNode()
				.setId(new LogicalTimestamp().setReplicaId(100L).setSequenceNumber(2L))
				.setValue(new ConValue(ConType.LONG, 42L));

		originalNodes = List.of(constant, new ValueNode().setId(rootId).setValue(constant.getId()));
		createSnapshot(tempFile, rootId, originalNodes);

		IndexedModelDecoder index = IndexedModelDecoder.build(tempFile);
		Map<LogicalTimestamp, IndexedModelDecoder.Entry> constantEntries = index.getEntriesForType(IndexedNodeCodecMapper.CONSTANT);
		assertEquals(1, constantEntries.size());

		try (SeekingNodeReader reader = new SeekingNodeReader(tempFile, index.getClockTable())) {
			// call under test
			Node readNode = reader.readNode(constant.getId(), constantEntries.get(constant.getId()));

			assertNotNull(readNode);
			assertTrue(readNode instanceof ConstantNode);
			assertEquals(constant, readNode);
		}
	}

	@Test
	public void testReadObjectNode() throws IOException {
		LogicalTimestamp rootId = new LogicalTimestamp().setReplicaId(100L).setSequenceNumber(1L);
		LogicalTimestamp constId = new LogicalTimestamp().setReplicaId(100L).setSequenceNumber(2L);
		ObjectNode object = new ObjectNode()
				.setId(new LogicalTimestamp().setReplicaId(100L).setSequenceNumber(3L))
				.setValue(Map.of("key", constId));

		originalNodes = List.of(
				new ConstantNode().setId(constId).setValue(new ConValue(ConType.STRING, "value")),
				object,
				new ValueNode().setId(rootId).setValue(object.getId())
		);
		createSnapshot(tempFile, rootId, originalNodes);

		IndexedModelDecoder index = IndexedModelDecoder.build(tempFile);
		Map<LogicalTimestamp, IndexedModelDecoder.Entry> objectEntries = index.getEntriesForType(IndexedNodeCodecMapper.OBJECT);
		assertEquals(1, objectEntries.size());

		try (SeekingNodeReader reader = new SeekingNodeReader(tempFile, index.getClockTable())) {
			// call under test
			Node readNode = reader.readNode(object.getId(), objectEntries.get(object.getId()));

			assertNotNull(readNode);
			assertTrue(readNode instanceof ObjectNode);
			assertEquals(object, readNode);
		}
	}

	@Test
	public void testReadValueNode() throws IOException {
		LogicalTimestamp rootId = new LogicalTimestamp().setReplicaId(100L).setSequenceNumber(1L);
		LogicalTimestamp constId = new LogicalTimestamp().setReplicaId(100L).setSequenceNumber(2L);
		ConstantNode constant = new ConstantNode().setId(constId).setValue(new ConValue(ConType.LONG, 42L));
		ValueNode value = new ValueNode().setId(rootId).setValue(constId);

		originalNodes = List.of(constant, value);
		createSnapshot(tempFile, rootId, originalNodes);

		IndexedModelDecoder index = IndexedModelDecoder.build(tempFile);
		Map<LogicalTimestamp, IndexedModelDecoder.Entry> valueEntries = index.getEntriesForType(IndexedNodeCodecMapper.VAL);
		assertEquals(1, valueEntries.size());

		try (SeekingNodeReader reader = new SeekingNodeReader(tempFile, index.getClockTable())) {
			// call under test
			Node readNode = reader.readNode(rootId, valueEntries.get(rootId));

			assertNotNull(readNode);
			assertTrue(readNode instanceof ValueNode);
			assertEquals(value, readNode);
		}
	}

	@Test
	public void testReadVectorNode() throws IOException {
		LogicalTimestamp rootId = new LogicalTimestamp().setReplicaId(100L).setSequenceNumber(1L);
		LogicalTimestamp constId = new LogicalTimestamp().setReplicaId(100L).setSequenceNumber(2L);
		LogicalTimestamp vecId = new LogicalTimestamp().setReplicaId(100L).setSequenceNumber(3L);
		VectorNode vector = new VectorNode()
				.setId(vecId)
				.setValues(Map.of(0, new ConstantNode().setId(constId)));

		originalNodes = List.of(
				new ConstantNode().setId(constId).setValue(new ConValue(ConType.LONG, 99L)),
				vector,
				new ValueNode().setId(rootId).setValue(vector.getId())
		);
		createSnapshot(tempFile, rootId, originalNodes);

		IndexedModelDecoder index = IndexedModelDecoder.build(tempFile);
		Map<LogicalTimestamp, IndexedModelDecoder.Entry> vectorEntries = index.getEntriesForType(IndexedNodeCodecMapper.VECTOR);
		assertEquals(1, vectorEntries.size());

		try (SeekingNodeReader reader = new SeekingNodeReader(tempFile, index.getClockTable())) {
			// call under test
			Node readNode = reader.readNode(vecId, vectorEntries.get(vecId));

			assertNotNull(readNode);
			assertTrue(readNode instanceof VectorNode);
			assertEquals(vector, readNode);
		}
	}

	@Test
	public void testReadArrayNode() throws IOException {
		LogicalTimestamp rootId = new LogicalTimestamp().setReplicaId(100L).setSequenceNumber(1L);
		LogicalTimestamp arrayId = new LogicalTimestamp().setReplicaId(100L).setSequenceNumber(2L);
		LogicalTimestamp dataId = new LogicalTimestamp().setReplicaId(100L).setSequenceNumber(3L);

		// Note: The decoder sets referenceNodeId to the array head (containerId) for the first element
		ArrayNode array = new ArrayNode()
				.setId(arrayId)
				.setElements(List.of(
						new RGANode()
								.setContainerId(arrayId)
								.setNodeId(arrayId)
								.setReferenceNodeId(arrayId)  // First element references the array head
								.setDataId(dataId)
								.setIsDeleted(false)
				));

		originalNodes = List.of(
				new ConstantNode().setId(dataId).setValue(new ConValue(ConType.BOOLEAN, true)),
				array,
				new ValueNode().setId(rootId).setValue(arrayId)
		);
		createSnapshot(tempFile, rootId, originalNodes);

		IndexedModelDecoder index = IndexedModelDecoder.build(tempFile);
		Map<LogicalTimestamp, IndexedModelDecoder.Entry> arrayEntries = index.getEntriesForType(IndexedNodeCodecMapper.ARRAY);
		assertEquals(1, arrayEntries.size());

		try (SeekingNodeReader reader = new SeekingNodeReader(tempFile, index.getClockTable())) {
			// call under test
			Node readNode = reader.readNode(arrayId, arrayEntries.get(arrayId));

			assertNotNull(readNode);
			assertTrue(readNode instanceof ArrayNode);
			assertEquals(array, readNode);
		}
	}

	@Test
	public void testReadNodes() throws IOException {
		LogicalTimestamp rootId = new LogicalTimestamp().setReplicaId(100L).setSequenceNumber(1L);
		ConstantNode const1 = new ConstantNode()
				.setId(new LogicalTimestamp().setReplicaId(100L).setSequenceNumber(2L))
				.setValue(new ConValue(ConType.LONG, 1L));
		ConstantNode const2 = new ConstantNode()
				.setId(new LogicalTimestamp().setReplicaId(100L).setSequenceNumber(3L))
				.setValue(new ConValue(ConType.LONG, 2L));
		ConstantNode const3 = new ConstantNode()
				.setId(new LogicalTimestamp().setReplicaId(100L).setSequenceNumber(4L))
				.setValue(new ConValue(ConType.LONG, 3L));

		originalNodes = List.of(const1, const2, const3, new ValueNode().setId(rootId).setValue(const1.getId()));
		createSnapshot(tempFile, rootId, originalNodes);

		IndexedModelDecoder index = IndexedModelDecoder.build(tempFile);
		Map<LogicalTimestamp, IndexedModelDecoder.Entry> constantEntries = index.getEntriesForType(IndexedNodeCodecMapper.CONSTANT);
		assertEquals(3, constantEntries.size());

		try (SeekingNodeReader reader = new SeekingNodeReader(tempFile, index.getClockTable())) {
			// call under test
			List<Node> readNodes = reader.readNodes(constantEntries.entrySet());

			assertEquals(3, readNodes.size());
			// The order matches the entry order, which is the file order
			assertTrue(readNodes.stream().allMatch(n -> n instanceof ConstantNode));
		}
	}

	@Test
	public void testReadNodesInReverseOrder() throws IOException {
		// Tests that seeking works correctly regardless of access order
		LogicalTimestamp rootId = new LogicalTimestamp().setReplicaId(100L).setSequenceNumber(1L);
		ConstantNode const1 = new ConstantNode()
				.setId(new LogicalTimestamp().setReplicaId(100L).setSequenceNumber(2L))
				.setValue(new ConValue(ConType.LONG, 1L));
		ConstantNode const2 = new ConstantNode()
				.setId(new LogicalTimestamp().setReplicaId(100L).setSequenceNumber(3L))
				.setValue(new ConValue(ConType.LONG, 2L));

		originalNodes = List.of(const1, const2, new ValueNode().setId(rootId).setValue(const1.getId()));
		createSnapshot(tempFile, rootId, originalNodes);

		IndexedModelDecoder index = IndexedModelDecoder.build(tempFile);
		Map<LogicalTimestamp, IndexedModelDecoder.Entry> constantEntries = index.getEntriesForType(IndexedNodeCodecMapper.CONSTANT);

		try (SeekingNodeReader reader = new SeekingNodeReader(tempFile, index.getClockTable())) {
			// Read in reverse order
			Node second = reader.readNode(const2.getId(), constantEntries.get(const2.getId()));
			Node first = reader.readNode(const1.getId(), constantEntries.get(const1.getId()));

			assertEquals(const2, second);
			assertEquals(const1, first);
		}
	}

	@Test
	public void testReadNodeWithNullEntry() throws IOException {
		LogicalTimestamp rootId = new LogicalTimestamp().setReplicaId(100L).setSequenceNumber(1L);
		LogicalTimestamp constId = new LogicalTimestamp().setReplicaId(100L).setSequenceNumber(2L);
		originalNodes = List.of(
				new ConstantNode().setId(constId).setValue(new ConValue(ConType.LONG, 42L)),
				new ValueNode().setId(rootId).setValue(constId)
		);
		createSnapshot(tempFile, rootId, originalNodes);

		IndexedModelDecoder index = IndexedModelDecoder.build(tempFile);
		try (SeekingNodeReader reader = new SeekingNodeReader(tempFile, index.getClockTable())) {
			// call under test
			assertThrows(IllegalArgumentException.class, () ->
					reader.readNode(null, null)
			);
		}
	}

	@Test
	public void testReadNodesWithNullList() throws IOException {
		LogicalTimestamp rootId = new LogicalTimestamp().setReplicaId(100L).setSequenceNumber(1L);
		LogicalTimestamp constId = new LogicalTimestamp().setReplicaId(100L).setSequenceNumber(2L);
		originalNodes = List.of(
				new ConstantNode().setId(constId).setValue(new ConValue(ConType.LONG, 42L)),
				new ValueNode().setId(rootId).setValue(constId)
		);
		createSnapshot(tempFile, rootId, originalNodes);

		IndexedModelDecoder index = IndexedModelDecoder.build(tempFile);
		try (SeekingNodeReader reader = new SeekingNodeReader(tempFile, index.getClockTable())) {
			// call under test
			assertThrows(IllegalArgumentException.class, () ->
					reader.readNodes(null)
			);
		}
	}

	@Test
	public void testConstructorWithNullPath() {
		ClockTable ct = new ClockTable(List.of(
				new LogicalTimestamp().setReplicaId(100L).setSequenceNumber(1L)
		));

		// call under test
		assertThrows(IllegalArgumentException.class, () ->
				new SeekingNodeReader(null, ct)
		);
	}

	@Test
	public void testConstructorWithNullClockTable() {
		// call under test
		assertThrows(IllegalArgumentException.class, () ->
				new SeekingNodeReader(tempFile, null)
		);
	}

	/**
	 * Helper to create a snapshot file.
	 */
	private void createSnapshot(Path file, LogicalTimestamp rootId, List<Node> nodes) throws IOException {
		byte[] encodedBytes;
		try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
			 IndexedModelEncoder encoder = new IndexedModelEncoder(baos, rootId)) {
			for (Node node : nodes) {
				encoder.writeNode(node);
			}
			encoder.close();
			encodedBytes = baos.toByteArray();
		}

		Files.write(file, encodedBytes);
	}
}
