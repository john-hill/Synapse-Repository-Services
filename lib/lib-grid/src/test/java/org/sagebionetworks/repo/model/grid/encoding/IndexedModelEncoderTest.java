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
import org.sagebionetworks.repo.model.grid.ClockTable;
import org.sagebionetworks.repo.model.grid.node.ConstantNode;
import org.sagebionetworks.repo.model.grid.node.Node;
import org.sagebionetworks.repo.model.grid.patch.ConType;
import org.sagebionetworks.repo.model.grid.patch.ConValue;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;

public class IndexedModelEncoderTest {

	private ClockTable clockTable;
	private LogicalTimestamp rootNodeId;

	@BeforeEach
	public void setUp() {
		clockTable = new ClockTable(List.of(
			new LogicalTimestamp().setReplicaId(100L).setSequenceNumber(50L),
			new LogicalTimestamp().setReplicaId(200L).setSequenceNumber(100L)
		));
		rootNodeId = new LogicalTimestamp().setReplicaId(100L).setSequenceNumber(1L);
	}

	@Test
	public void testEncodeEmptyModel() throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (IndexedModelEncoder encoder = new IndexedModelEncoder(out, clockTable, rootNodeId)) {
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

		try (IndexedModelEncoder encoder = new IndexedModelEncoder(out, clockTable, rootNodeId)) {
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

		try (IndexedModelEncoder encoder = new IndexedModelEncoder(out, clockTable, rootNodeId)) {
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

		try (IndexedModelEncoder encoder = new IndexedModelEncoder(out, clockTable, rootNodeId)) {
			assertThrows(IllegalArgumentException.class, () -> {
				encoder.writeNode(nodeWithoutId);
			});
		}
	}

	@Test
	public void testEncodeNodeWithUnknownReplicaId() throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();

		ConstantNode nodeWithUnknownReplica = new ConstantNode()
			.setId(new LogicalTimestamp().setReplicaId(999L).setSequenceNumber(1L))
			.setValue(new ConValue(ConType.LONG, 42L));

		try (IndexedModelEncoder encoder = new IndexedModelEncoder(out, clockTable, rootNodeId)) {
			assertThrows(IllegalArgumentException.class, () -> {
				encoder.writeNode(nodeWithUnknownReplica);
			});
		}
	}

	@Test
	public void testWriteAfterClose() throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		IndexedModelEncoder encoder = new IndexedModelEncoder(out, clockTable, rootNodeId);
		encoder.close();

		ConstantNode node = new ConstantNode()
			.setId(new LogicalTimestamp().setReplicaId(100L).setSequenceNumber(2L))
			.setValue(new ConValue(ConType.LONG, 42L));

		assertThrows(IllegalStateException.class, () -> {
			encoder.writeNode(node);
		});
	}

	@Test
	public void testSetClockTableWithValidUpdate() throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		IndexedModelEncoder encoder = new IndexedModelEncoder(out, clockTable, rootNodeId);

		// Create a new clock table with same replica IDs in same order but different sequence numbers
		ClockTable updatedClockTable = new ClockTable(List.of(
			new LogicalTimestamp().setReplicaId(100L).setSequenceNumber(150L),
			new LogicalTimestamp().setReplicaId(200L).setSequenceNumber(250L)
		));

		// Should not throw
		encoder.setClockTable(updatedClockTable);
		encoder.close();
	}

	@Test
	public void testSetClockTableWithNull() throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		IndexedModelEncoder encoder = new IndexedModelEncoder(out, clockTable, rootNodeId);

		assertThrows(IllegalArgumentException.class, () -> {
			encoder.setClockTable(null);
		});
		encoder.close();
	}

	@Test
	public void testSetClockTableWithDifferentSize() throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		IndexedModelEncoder encoder = new IndexedModelEncoder(out, clockTable, rootNodeId);

		// Create a clock table with different size
		ClockTable differentSizeClockTable = new ClockTable(List.of(
			new LogicalTimestamp().setReplicaId(100L).setSequenceNumber(50L),
			new LogicalTimestamp().setReplicaId(200L).setSequenceNumber(100L),
			new LogicalTimestamp().setReplicaId(300L).setSequenceNumber(150L)
		));

		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
			encoder.setClockTable(differentSizeClockTable);
		});
		assertTrue(exception.getMessage().contains("clock table sizes differ"));
		encoder.close();
	}

	@Test
	public void testSetClockTableWithDifferentReplicaIds() throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		IndexedModelEncoder encoder = new IndexedModelEncoder(out, clockTable, rootNodeId);

		// Create a clock table with same size but different replica IDs
		ClockTable differentReplicaIds = new ClockTable(List.of(
			new LogicalTimestamp().setReplicaId(100L).setSequenceNumber(50L),
			new LogicalTimestamp().setReplicaId(300L).setSequenceNumber(100L) // Changed from 200L to 300L
		));

		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
			encoder.setClockTable(differentReplicaIds);
		});
		assertTrue(exception.getMessage().contains("same replica IDs in the same order"));
		encoder.close();
	}

	@Test
	public void testSetClockTableWithDifferentOrder() throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		IndexedModelEncoder encoder = new IndexedModelEncoder(out, clockTable, rootNodeId);

		// Create a clock table with same replica IDs but in different order
		ClockTable differentOrder = new ClockTable(List.of(
			new LogicalTimestamp().setReplicaId(200L).setSequenceNumber(100L), // Swapped order
			new LogicalTimestamp().setReplicaId(100L).setSequenceNumber(50L)
		));

		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
			encoder.setClockTable(differentOrder);
		});
		assertTrue(exception.getMessage().contains("same replica IDs in the same order"));
		encoder.close();
	}

}
