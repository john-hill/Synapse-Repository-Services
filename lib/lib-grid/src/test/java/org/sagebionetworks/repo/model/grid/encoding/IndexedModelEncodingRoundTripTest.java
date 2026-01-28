package org.sagebionetworks.repo.model.grid.encoding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.sagebionetworks.repo.model.grid.ClockTable;
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
 * Round-trip tests for the indexed model encoder/decoder.
 * These tests verify that encoding followed by decoding produces the original data.
 */
public class IndexedModelEncodingRoundTripTest {


    @Test
    public void testWriteModelToFile() throws IOException {
        // Encode and decode a JSON CRDT model. If making changes to this test, also consider attempting to read the
        // file using the json-joy JavaScript library.

        LogicalTimestamp testRootNodeId = new LogicalTimestamp().setReplicaId(100L).setSequenceNumber(2L);

        List<Node> nodes = new ArrayList<>();
        nodes.add(new ObjectNode()
                .setId(new LogicalTimestamp().setReplicaId(100L).setSequenceNumber(2L))
                .setValue(Map.of(
                        "const", new LogicalTimestamp().setReplicaId(100L).setSequenceNumber(3L),
                        "vector", new LogicalTimestamp().setReplicaId(200L).setSequenceNumber(4L),
                        "array", new LogicalTimestamp().setReplicaId(200L).setSequenceNumber(10L)
                )));
        nodes.add(new ConstantNode()
                .setId(new LogicalTimestamp().setReplicaId(100L).setSequenceNumber(3L))
                .setValue(new ConValue(ConType.LONG, 42L)));
        nodes.add(new VectorNode()
                .setId(new LogicalTimestamp().setReplicaId(200L).setSequenceNumber(4L))
                .setValues(Map.of(0, new ConstantNode()
                                .setId(new LogicalTimestamp().setReplicaId(200L).setSequenceNumber(6L)),
                        1, new ConstantNode()
                                .setId(new LogicalTimestamp().setReplicaId(100L).setSequenceNumber(7L)),
                        3, new ConstantNode()
                                .setId(new LogicalTimestamp().setReplicaId(200L).setSequenceNumber(8L)))));
        nodes.add(new ConstantNode()
                .setId(new LogicalTimestamp().setReplicaId(200L).setSequenceNumber(6L))
                .setValue(new ConValue(ConType.STRING, "hello")));
        nodes.add(new ConstantNode()
                .setId(new LogicalTimestamp().setReplicaId(100L).setSequenceNumber(7L))
                .setValue(new ConValue(ConType.DOUBLE, 3.14)));
        nodes.add(new ConstantNode()
                .setId(new LogicalTimestamp().setReplicaId(200L).setSequenceNumber(8L))
                .setValue(new ConValue(ConType.UNDEFINED, null)));

        nodes.add(new ArrayNode()
                .setId(new LogicalTimestamp().setReplicaId(200L).setSequenceNumber(10L))
                .setElements(List.of(
                        new RGANode()
                                .setContainerId(new LogicalTimestamp().setReplicaId(200L).setSequenceNumber(10L))
                                .setNodeId(new LogicalTimestamp().setReplicaId(200L).setSequenceNumber(10L))
                                .setDataId(new LogicalTimestamp().setReplicaId(100L).setSequenceNumber(12L))
                                .setIsDeleted(false),
                        new RGANode()
                                .setContainerId(new LogicalTimestamp().setReplicaId(200L).setSequenceNumber(10L))
                                .setNodeId(new LogicalTimestamp().setReplicaId(200L).setSequenceNumber(11L))
                                .setDataId(new LogicalTimestamp().setReplicaId(200L).setSequenceNumber(13L))
                                .setReferenceNodeId(new LogicalTimestamp().setReplicaId(200L).setSequenceNumber(10L))
                                .setIsDeleted(false)
                )));
        nodes.add(new ConstantNode()
                .setId(new LogicalTimestamp().setReplicaId(100L).setSequenceNumber(12L))
                .setValue(new ConValue(ConType.BOOLEAN, true)));
        nodes.add(new ConstantNode()
                .setId(new LogicalTimestamp().setReplicaId(200L).setSequenceNumber(13L))
                .setValue(new ConValue(ConType.NULL, null)));


        byte[] encodedBytes;
        try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream()) {
            try (IndexedModelEncoder encoder = new IndexedModelEncoder(byteArrayOutputStream, testRootNodeId)) {
                nodes.forEach(node -> {
                    try {
                        encoder.writeNode(node);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
            encodedBytes = byteArrayOutputStream.toByteArray();
        }
        assertTrue(encodedBytes.length > 0);

        // Now read back the file and verify the nodes
        Supplier<ByteArrayInputStream> supplier = () -> new ByteArrayInputStream(encodedBytes);

        ClockTable expectedClockTable = new ClockTable(List.of(
                new LogicalTimestamp().setReplicaId(100L).setSequenceNumber(12L),
                new LogicalTimestamp().setReplicaId(200L).setSequenceNumber(13L)
        ));

        List<Node> decodedNodes = new ArrayList<>();
        try (IndexedModelDecoder decoder = new IndexedModelDecoder(supplier)) {
            assertEquals(testRootNodeId, decoder.getRootNodeId(), "Root node ID should match");
            assertEquals(expectedClockTable, decoder.getClockTable(), "Clock table should match");
            decoder.iterator().forEachRemaining(decodedNodes::add);
        }

        assertEquals(nodes, decodedNodes, "Decoded nodes should match original nodes");
    }
}
