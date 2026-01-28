package org.sagebionetworks.repo.model.grid.node;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;

public class ArrayNodeTest {

	private LogicalTimestamp nodeId;
	private LogicalTimestamp rgaNodeId1;
	private LogicalTimestamp rgaDataId1;
	private LogicalTimestamp rgaNodeId2;
	private LogicalTimestamp rgaDataId2;
	private LogicalTimestamp rgaNodeId3;
	private LogicalTimestamp rgaDataId3;

	@BeforeEach
	public void before() {
		nodeId = new LogicalTimestamp().setReplicaId(1L).setSequenceNumber(2L);
		rgaNodeId1 = new LogicalTimestamp().setReplicaId(3L).setSequenceNumber(4L);
		rgaDataId1 = new LogicalTimestamp().setReplicaId(5L).setSequenceNumber(6L);
		rgaNodeId2 = new LogicalTimestamp().setReplicaId(7L).setSequenceNumber(8L);
		rgaDataId2 = new LogicalTimestamp().setReplicaId(9L).setSequenceNumber(10L);
		rgaNodeId3 = new LogicalTimestamp().setReplicaId(11L).setSequenceNumber(12L);
		rgaDataId3 = new LogicalTimestamp().setReplicaId(13L).setSequenceNumber(14L);
	}

	@Test
	public void testStreamReferencedTimestampsWithElements() {
		ArrayNode array = new ArrayNode().setId(nodeId);
		List<RGANode> elements = new ArrayList<>();
		elements.add(new RGANode().setNodeId(rgaNodeId1).setDataId(rgaDataId1));
		elements.add(new RGANode().setNodeId(rgaNodeId2).setDataId(rgaDataId2));
		elements.add(new RGANode().setNodeId(rgaNodeId3).setDataId(rgaDataId3));
		array.setElements(elements);

		// call under test
		List<LogicalTimestamp> timestamps = array.streamReferencedTimestamps().collect(Collectors.toList());

		assertEquals(7, timestamps.size());
		assertEquals(nodeId, timestamps.get(0)); // node ID first
		assertEquals(rgaNodeId1, timestamps.get(1));
		assertEquals(rgaDataId1, timestamps.get(2));
		assertEquals(rgaNodeId2, timestamps.get(3));
		assertEquals(rgaDataId2, timestamps.get(4));
		assertEquals(rgaNodeId3, timestamps.get(5));
		assertEquals(rgaDataId3, timestamps.get(6));
	}

	@Test
	public void testStreamReferencedTimestampsWithNullElements() {
		ArrayNode array = new ArrayNode().setId(nodeId).setElements(null);

		// call under test
		List<LogicalTimestamp> timestamps = array.streamReferencedTimestamps().collect(Collectors.toList());

		assertEquals(1, timestamps.size());
		assertEquals(nodeId, timestamps.get(0)); // only node ID
	}

	@Test
	public void testStreamReferencedTimestampsWithEmptyElements() {
		ArrayNode array = new ArrayNode().setId(nodeId).setElements(new ArrayList<>());

		// call under test
		List<LogicalTimestamp> timestamps = array.streamReferencedTimestamps().collect(Collectors.toList());

		assertEquals(1, timestamps.size());
		assertEquals(nodeId, timestamps.get(0)); // only node ID
	}

	@Test
	public void testStreamReferencedTimestampsWithNullNodeId() {
		ArrayNode array = new ArrayNode().setId(nodeId);
		List<RGANode> elements = new ArrayList<>();
		elements.add(new RGANode().setNodeId(rgaNodeId1).setDataId(rgaDataId1));
		elements.add(new RGANode().setNodeId(null).setDataId(rgaDataId2)); // null nodeId
		elements.add(new RGANode().setNodeId(rgaNodeId3).setDataId(rgaDataId3));
		array.setElements(elements);

		// call under test
		List<LogicalTimestamp> timestamps = array.streamReferencedTimestamps().collect(Collectors.toList());

		assertEquals(6, timestamps.size());
		assertEquals(nodeId, timestamps.get(0));
		assertEquals(rgaNodeId1, timestamps.get(1));
		assertEquals(rgaDataId1, timestamps.get(2));
		assertEquals(rgaDataId2, timestamps.get(3)); // dataId still included
		assertEquals(rgaNodeId3, timestamps.get(4));
		assertEquals(rgaDataId3, timestamps.get(5));
	}

	@Test
	public void testStreamReferencedTimestampsWithNullDataId() {
		ArrayNode array = new ArrayNode().setId(nodeId);
		List<RGANode> elements = new ArrayList<>();
		elements.add(new RGANode().setNodeId(rgaNodeId1).setDataId(rgaDataId1));
		elements.add(new RGANode().setNodeId(rgaNodeId2).setDataId(null)); // null dataId
		elements.add(new RGANode().setNodeId(rgaNodeId3).setDataId(rgaDataId3));
		array.setElements(elements);

		// call under test
		List<LogicalTimestamp> timestamps = array.streamReferencedTimestamps().collect(Collectors.toList());

		assertEquals(6, timestamps.size());
		assertEquals(nodeId, timestamps.get(0));
		assertEquals(rgaNodeId1, timestamps.get(1));
		assertEquals(rgaDataId1, timestamps.get(2));
		assertEquals(rgaNodeId2, timestamps.get(3)); // nodeId still included
		assertEquals(rgaNodeId3, timestamps.get(4));
		assertEquals(rgaDataId3, timestamps.get(5));
	}

	@Test
	public void testStreamReferencedTimestampsWithBothNull() {
		ArrayNode array = new ArrayNode().setId(nodeId);
		List<RGANode> elements = new ArrayList<>();
		elements.add(new RGANode().setNodeId(rgaNodeId1).setDataId(rgaDataId1));
		elements.add(new RGANode().setNodeId(null).setDataId(null)); // both null
		elements.add(new RGANode().setNodeId(rgaNodeId3).setDataId(rgaDataId3));
		array.setElements(elements);

		// call under test
		List<LogicalTimestamp> timestamps = array.streamReferencedTimestamps().collect(Collectors.toList());

		assertEquals(5, timestamps.size());
		assertEquals(nodeId, timestamps.get(0));
		assertEquals(rgaNodeId1, timestamps.get(1));
		assertEquals(rgaDataId1, timestamps.get(2));
		assertEquals(rgaNodeId3, timestamps.get(3));
		assertEquals(rgaDataId3, timestamps.get(4));
	}

}
