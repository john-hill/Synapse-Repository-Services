package org.sagebionetworks.repo.model.grid.encoding;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sagebionetworks.repo.model.grid.node.ConstantNode;
import org.sagebionetworks.repo.model.grid.node.Node;
import org.sagebionetworks.repo.model.grid.patch.ConType;
import org.sagebionetworks.repo.model.grid.patch.ConValue;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;

public class IndexedModelEncoderTest {

	private LogicalTimestamp rootNodeId;

	@BeforeEach
	public void setUp() {
		rootNodeId = new LogicalTimestamp().setReplicaId(100L).setSequenceNumber(1L);
	}

	@Test
	public void testEncodeEmptyModel() throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (IndexedModelEncoder encoder = new IndexedModelEncoder(out, rootNodeId)) {
			// No nodes added
		}

		byte[] result = out.toByteArray();
		assertNotNull(result);
		assertTrue(result.length > 0, "Encoded model should not be empty");
	}

	@Test
	public void testEncodeModel() throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		List<Node> nodes = new ArrayList<>();
		nodes.add(new ConstantNode()
				.setId(new LogicalTimestamp().setReplicaId(100L).setSequenceNumber(2L))
				.setValue(new ConValue(ConType.LONG, 42L)));
		nodes.add(new ConstantNode()
				.setId(new LogicalTimestamp().setReplicaId(100L).setSequenceNumber(3L))
				.setValue(new ConValue(ConType.STRING, "hello")));
		nodes.add(new ConstantNode()
				.setId(new LogicalTimestamp().setReplicaId(200L).setSequenceNumber(5L))
				.setValue(new ConValue(ConType.BOOLEAN, true)));

		try (IndexedModelEncoder encoder = new IndexedModelEncoder(out, rootNodeId)) {
			for (Node node : nodes) {
				encoder.writeNode(node);
			}
		}

		byte[] result = out.toByteArray();
		assertNotNull(result);
		assertTrue(result.length > 0);

		// IndexedModelEncodingRoundTripTest will verify that the model can be decoded.
	}

	@Test
	public void testEncodeNullNode() throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();

		try (IndexedModelEncoder encoder = new IndexedModelEncoder(out, rootNodeId)) {
			assertThrows(IllegalArgumentException.class, () -> {
				encoder.writeNode(null);
			});
		}
	}

	@Test
	public void testEncodeNodeWithNullId() throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();

		ConstantNode nodeWithoutId = new ConstantNode()
			.setValue(new ConValue(ConType.LONG, 42L));

		try (IndexedModelEncoder encoder = new IndexedModelEncoder(out, rootNodeId)) {
			assertThrows(IllegalArgumentException.class, () -> {
				encoder.writeNode(nodeWithoutId);
			});
		}
	}

	@Test
	public void testWriteAfterClose() throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		IndexedModelEncoder encoder = new IndexedModelEncoder(out, rootNodeId);
		encoder.close();

		ConstantNode node = new ConstantNode()
			.setId(new LogicalTimestamp().setReplicaId(100L).setSequenceNumber(2L))
			.setValue(new ConValue(ConType.LONG, 42L));

		assertThrows(IllegalStateException.class, () -> {
			encoder.writeNode(node);
		});
	}

}
