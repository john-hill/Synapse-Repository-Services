package org.sagebionetworks.repo.model.grid.encoding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Supplier;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.sagebionetworks.repo.model.grid.ClockTable;
import org.sagebionetworks.repo.model.grid.encoding.IndexedModelDecoder.DecodedModel;
import org.sagebionetworks.repo.model.grid.node.ArrayNode;
import org.sagebionetworks.repo.model.grid.node.ConstantNode;
import org.sagebionetworks.repo.model.grid.node.Node;
import org.sagebionetworks.repo.model.grid.node.ObjectNode;
import org.sagebionetworks.repo.model.grid.node.RGANode;
import org.sagebionetworks.repo.model.grid.node.VectorNode;
import org.sagebionetworks.repo.model.grid.patch.ConType;
import org.sagebionetworks.repo.model.grid.patch.ConValue;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;

/**
 * Test files were created using json-joy's indexed model encoder.
 */
public class IndexedModelDecoderTest {

	private Supplier<InputStream> loadResource(String resourcePath) {
		return () -> getClass().getResourceAsStream(resourcePath);
	}

	@Test
	public void testDecodeEmptyObject() throws IOException {
		try (IndexedModelDecoder decoder = new IndexedModelDecoder(loadResource("/indexed-model/empty-object.cbor"))) {
			// Verify clock table
			ClockTable clockTable = decoder.getClockTable();
			assertNotNull(clockTable);
			assertEquals(1, clockTable.getClocks().size());
			assertEquals(new LogicalTimestamp().setReplicaId(1L).setSequenceNumber(2L), clockTable.getClocks().get(0));

			// Verify root node ID
			LogicalTimestamp expectedRootId = new LogicalTimestamp().setReplicaId(1L).setSequenceNumber(1L);
			assertEquals(expectedRootId, decoder.getRootNodeId());

			// Verify nodes
			List<Node> nodes = new ArrayList<>();
			Node node;
			while ((node = decoder.readNode()) != null) {
				nodes.add(node);
			}
			assertEquals(1, nodes.size());

			// Empty object
			ObjectNode expected = new ObjectNode()
				.setId(new LogicalTimestamp().setReplicaId(1L).setSequenceNumber(1L))
				.setValue(java.util.Map.of());
			assertEquals(expected, nodes.get(0));
		}
	}

	@Test
	public void testDecodeMultipleReplicas() throws IOException {
		try (IndexedModelDecoder decoder = new IndexedModelDecoder(loadResource("/indexed-model/multiple-replicas.cbor"))) {
			// Verify clock table
			ClockTable clockTable = decoder.getClockTable();
			assertNotNull(clockTable);
			assertEquals(1, clockTable.getClocks().size());
			assertEquals(new LogicalTimestamp().setReplicaId(1L).setSequenceNumber(8L), clockTable.getClocks().get(0));

			// Verify root node ID
			assertEquals(new LogicalTimestamp().setReplicaId(1L).setSequenceNumber(5L), decoder.getRootNodeId());

			// Verify nodes
			List<Node> nodes = new ArrayList<>();
			Node node;
			while ((node = decoder.readNode()) != null) {
				nodes.add(node);
			}
			assertEquals(2, nodes.size());

			// Node 0: ObjectNode with "rep2" key
			ObjectNode expectedObj = new ObjectNode()
				.setId(new LogicalTimestamp().setReplicaId(1L).setSequenceNumber(5L))
				.setValue(java.util.Map.of("rep2", new LogicalTimestamp().setReplicaId(1L).setSequenceNumber(6L)));
			assertEquals(expectedObj, nodes.get(0));

			// Node 1: ConstantNode with string value
			ConstantNode expectedConst = new ConstantNode()
				.setId(new LogicalTimestamp().setReplicaId(1L).setSequenceNumber(6L))
				.setValue(new ConValue(ConType.STRING, "From replica 2"));
			assertEquals(expectedConst, nodes.get(1));
		}
	}

	@Test
	public void testDecodeAllTypes() throws IOException {
		try (IndexedModelDecoder decoder = new IndexedModelDecoder(loadResource("/indexed-model/all-types.cbor"))) {
			// Verify clock table
			ClockTable clockTable = decoder.getClockTable();
			assertNotNull(clockTable);
			assertEquals(1, clockTable.getClocks().size());
			assertEquals(new LogicalTimestamp().setReplicaId(1L).setSequenceNumber(24L), clockTable.getClocks().get(0));

			// Verify root node ID
			assertEquals(new LogicalTimestamp().setReplicaId(1L).setSequenceNumber(1L), decoder.getRootNodeId());

			// Verify nodes
			List<Node> nodes = new ArrayList<>();
			Node node;
			while ((node = decoder.readNode()) != null) {
				nodes.add(node);
			}
			assertEquals(17, nodes.size());

			// Node 0: Root ObjectNode with 8 keys
			assertTrue(nodes.get(0) instanceof ObjectNode);
			ObjectNode rootObj = (ObjectNode) nodes.get(0);
			assertEquals(1L, rootObj.getId().getSequenceNumber());
			assertNotNull(rootObj.getValue());
			assertEquals(8, rootObj.getValue().size());
			assertTrue(rootObj.getValue().containsKey("str"));
			assertTrue(rootObj.getValue().containsKey("num"));
			assertTrue(rootObj.getValue().containsKey("bool"));
			assertTrue(rootObj.getValue().containsKey("nil"));
			assertTrue(rootObj.getValue().containsKey("undf"));
			assertTrue(rootObj.getValue().containsKey("arr"));
			assertTrue(rootObj.getValue().containsKey("vec"));
			assertTrue(rootObj.getValue().containsKey("obj"));

			// Node 1-5: Various ConstantNode types
			assertEquals(new ConstantNode()
				.setId(new LogicalTimestamp().setReplicaId(1L).setSequenceNumber(2L))
				.setValue(new ConValue(ConType.STRING, "Hello, json-joy!")), nodes.get(1));

			assertEquals(new ConstantNode()
				.setId(new LogicalTimestamp().setReplicaId(1L).setSequenceNumber(3L))
				.setValue(new ConValue(ConType.LONG, 42L)), nodes.get(2));

			assertEquals(new ConstantNode()
				.setId(new LogicalTimestamp().setReplicaId(1L).setSequenceNumber(4L))
				.setValue(new ConValue(ConType.BOOLEAN, true)), nodes.get(3));

			assertEquals(new ConstantNode()
				.setId(new LogicalTimestamp().setReplicaId(1L).setSequenceNumber(5L))
				.setValue(new ConValue(ConType.NULL, JSONObject.NULL)), nodes.get(4));

			assertEquals(new ConstantNode()
				.setId(new LogicalTimestamp().setReplicaId(1L).setSequenceNumber(6L))
				.setValue(new ConValue(ConType.UNDEFINED, null)), nodes.get(5));

			// Node 6: ArrayNode with 3 elements
			ArrayNode expectedArray = new ArrayNode()
				.setId(new LogicalTimestamp().setReplicaId(1L).setSequenceNumber(7L))
				.setElements(List.of(
					new RGANode()
						.setNodeId(new LogicalTimestamp().setReplicaId(1L).setSequenceNumber(11L))
						.setContainerId(new LogicalTimestamp().setReplicaId(1L).setSequenceNumber(7L))
						.setDataId(new LogicalTimestamp().setReplicaId(1L).setSequenceNumber(8L))
						.setReferenceNodeId(null),
					new RGANode()
						.setNodeId(new LogicalTimestamp().setReplicaId(1L).setSequenceNumber(12L))
						.setContainerId(new LogicalTimestamp().setReplicaId(1L).setSequenceNumber(7L))
						.setDataId(new LogicalTimestamp().setReplicaId(1L).setSequenceNumber(9L))
						.setReferenceNodeId(new LogicalTimestamp().setReplicaId(1L).setSequenceNumber(11L)),
					new RGANode()
						.setNodeId(new LogicalTimestamp().setReplicaId(1L).setSequenceNumber(13L))
						.setContainerId(new LogicalTimestamp().setReplicaId(1L).setSequenceNumber(7L))
						.setDataId(new LogicalTimestamp().setReplicaId(1L).setSequenceNumber(10L))
						.setReferenceNodeId(new LogicalTimestamp().setReplicaId(1L).setSequenceNumber(12L))
				));
			assertEquals(expectedArray, nodes.get(6));

			// Node 7-9: Array element ConstantNodes
			assertEquals(new ConstantNode()
				.setId(new LogicalTimestamp().setReplicaId(1L).setSequenceNumber(8L))
				.setValue(new ConValue(ConType.LONG, 1L)), nodes.get(7));

			assertEquals(new ConstantNode()
				.setId(new LogicalTimestamp().setReplicaId(1L).setSequenceNumber(9L))
				.setValue(new ConValue(ConType.STRING, "two")), nodes.get(8));

			assertEquals(new ConstantNode()
				.setId(new LogicalTimestamp().setReplicaId(1L).setSequenceNumber(10L))
				.setValue(new ConValue(ConType.BOOLEAN, false)), nodes.get(9));

			// Node 10: VectorNode with 3 values
			assertTrue(nodes.get(10) instanceof VectorNode);
			VectorNode vecNode = (VectorNode) nodes.get(10);
			assertEquals(14L, vecNode.getId().getSequenceNumber());
			assertNotNull(vecNode.getValues());
			assertEquals(3, vecNode.getValues().size());

			// Node 11-13: Vector element ConstantNodes
			assertEquals(new ConstantNode()
				.setId(new LogicalTimestamp().setReplicaId(1L).setSequenceNumber(15L))
				.setValue(new ConValue(ConType.LONG, 1L)), nodes.get(11));

			assertEquals(new ConstantNode()
				.setId(new LogicalTimestamp().setReplicaId(1L).setSequenceNumber(16L))
				.setValue(new ConValue(ConType.LONG, 2L)), nodes.get(12));

			assertEquals(new ConstantNode()
				.setId(new LogicalTimestamp().setReplicaId(1L).setSequenceNumber(17L))
				.setValue(new ConValue(ConType.LONG, 3L)), nodes.get(13));

			// Node 14: Nested ObjectNode with 2 keys
			ObjectNode expectedNestedObj = new ObjectNode()
				.setId(new LogicalTimestamp().setReplicaId(1L).setSequenceNumber(19L))
				.setValue(java.util.Map.of(
					"a", new LogicalTimestamp().setReplicaId(1L).setSequenceNumber(20L),
					"b", new LogicalTimestamp().setReplicaId(1L).setSequenceNumber(21L)
				));
			assertEquals(expectedNestedObj, nodes.get(14));

			// Node 15-16: Object value ConstantNodes
			assertEquals(new ConstantNode()
				.setId(new LogicalTimestamp().setReplicaId(1L).setSequenceNumber(20L))
				.setValue(new ConValue(ConType.STRING, "A")), nodes.get(15));

			assertEquals(new ConstantNode()
				.setId(new LogicalTimestamp().setReplicaId(1L).setSequenceNumber(21L))
				.setValue(new ConValue(ConType.STRING, "B")), nodes.get(16));
		}
	}

	@Test
	public void testIteratorWithEmptyObject() throws IOException {
		try (IndexedModelDecoder decoder = new IndexedModelDecoder(loadResource("/indexed-model/empty-object.cbor"))) {
			int count = 0;
			for (Node node : decoder) {
				assertNotNull(node);
				count++;
			}
			assertEquals(1, count);
		}
	}

	@Test
	public void testIteratorNoSuchElement() throws IOException {
		try (IndexedModelDecoder decoder = new IndexedModelDecoder(loadResource("/indexed-model/empty-object.cbor"))) {
			Iterator<Node> iter = decoder.iterator();
			// Consume all nodes
			while (iter.hasNext()) {
				iter.next();
			}
			// Should throw when trying to get next after exhausted
			assertThrows(NoSuchElementException.class, () -> iter.next());
		}
	}

	@Test
	public void testDecodeModelStaticMethod() throws IOException {
		DecodedModel result = IndexedModelDecoder.decodeModel(loadResource("/indexed-model/multiple-replicas.cbor"));

		assertNotNull(result);
		assertNotNull(result.getClockTable());
		assertNotNull(result.getRootNodeId());
		assertNotNull(result.getNodes());

		assertEquals(2, result.getNodes().size());
		assertEquals(1L, result.getRootNodeId().getReplicaId());
		assertEquals(5L, result.getRootNodeId().getSequenceNumber());
	}

	@Test
	public void testNullInputStream() {
		assertThrows(IllegalArgumentException.class, () -> {
			new IndexedModelDecoder(null);
		});
	}

	@Test
	public void testInvalidCBORData() {
		byte[] invalidData = new byte[] { 0x00, 0x01, 0x02 };
		assertThrows(RuntimeException.class, () -> {
			new IndexedModelDecoder(() -> new java.io.ByteArrayInputStream(invalidData));
		});
	}
}
