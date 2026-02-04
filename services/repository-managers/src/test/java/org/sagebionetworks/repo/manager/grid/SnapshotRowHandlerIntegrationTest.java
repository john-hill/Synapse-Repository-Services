package org.sagebionetworks.repo.manager.grid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.StackConfiguration;
import org.sagebionetworks.aws.SynapseS3Client;
import org.sagebionetworks.repo.manager.schema.JsonSchemaValidationManager;
import org.sagebionetworks.repo.model.grid.encoding.IndexedModelDecoder;
import org.sagebionetworks.repo.model.grid.node.ArrayNode;
import org.sagebionetworks.repo.model.grid.node.Node;
import org.sagebionetworks.repo.model.grid.node.ObjectNode;
import org.sagebionetworks.repo.model.grid.node.ValueNode;
import org.sagebionetworks.repo.model.grid.node.VectorNode;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.repo.model.table.Row;
import org.sagebionetworks.util.FileProvider;

import com.amazonaws.services.s3.model.CompleteMultipartUploadRequest;
import com.amazonaws.services.s3.model.CompleteMultipartUploadResult;
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadResult;
import com.amazonaws.services.s3.model.UploadPartRequest;
import com.amazonaws.services.s3.model.UploadPartResult;

/**
 * Integration test for SnapshotRowHandler that writes actual CBOR files
 * and verifies they can be read back correctly.
 *
 * This test is separate from the unit tests because it performs actual file I/O
 * and decoding, making it slower and more suitable for integration testing.
 */
@ExtendWith(MockitoExtension.class)
public class SnapshotRowHandlerIntegrationTest {

	@Mock
	private SnapshotStore mockSnapshotStore;

	@Mock
	private FileProvider mockFileProvider;

	@Mock
	private SynapseS3Client mockS3Client;

	@Mock
	private StackConfiguration mockConfig;

	@Mock
	private JsonSchemaValidationManager mockValidationManager;

	@TempDir
	File tempDir;

	private String sessionId;
	private Long replicaId;
	private List<ColumnModel> schema;
	private List<Integer> requiredColumnIndices;
	private Long createdByUserId;
	private String stackName;
	private File tempFile;

	@BeforeEach
	public void before() throws IOException {
		sessionId = "s123";
		replicaId = 19L;
		schema = List.of(
			new ColumnModel().setColumnType(ColumnType.STRING).setName("aString"),
			new ColumnModel().setColumnType(ColumnType.INTEGER).setName("anInt")
		);
		requiredColumnIndices = Collections.emptyList();
		createdByUserId = 999L;
		stackName = "dev";

		// Create a real temp file for testing
		tempFile = new File(tempDir, "snapshot-integration-test.cbor");

		lenient().when(mockConfig.getStack()).thenReturn(stackName);
		lenient().when(mockFileProvider.createTempFile("snapshot", ".cbor")).thenReturn(tempFile);

		// Set up common S3 mocks
		InitiateMultipartUploadResult initiateResult = new InitiateMultipartUploadResult();
		initiateResult.setUploadId("test-upload-id");
		lenient().when(mockS3Client.initiateMultipartUpload(any(InitiateMultipartUploadRequest.class)))
				.thenReturn(initiateResult);

		CompleteMultipartUploadResult completeResult = new CompleteMultipartUploadResult();
		completeResult.setKey("snapshot/test/file.cbor");
		completeResult.setBucketName(stackName + ".grid.snapshot.sagebase.org");
		lenient().when(mockS3Client.completeMultipartUpload(any(CompleteMultipartUploadRequest.class)))
				.thenReturn(completeResult);
	}

	/**
	 * Integration test that writes a snapshot to a file and reads it back to verify
	 * the entire encoding/decoding workflow.
	 */
	@Test
	public void testWriteAndReadSnapshotFile() throws IOException {
		// Capture the snapshot bytes during upload
		final byte[][] capturedBytes = new byte[1][];

		when(mockS3Client.uploadPart(any(UploadPartRequest.class))).thenAnswer(invocation -> {
			UploadPartRequest request = invocation.getArgument(0);
			// Copy the file bytes before they're uploaded
			if (capturedBytes[0] == null) {
				capturedBytes[0] = Files.readAllBytes(request.getFile().toPath());
			}
			UploadPartResult result = new UploadPartResult();
			result.setETag("etag-1");
			result.setPartNumber(1);
			return result;
		});

		// Write snapshot file
		try (SnapshotRowHandler handler = new SnapshotRowHandler(mockSnapshotStore, sessionId, replicaId, schema,
				requiredColumnIndices, mockFileProvider, mockS3Client, mockConfig, createdByUserId, mockValidationManager, null)) {
			handler.nextRow(new Row().setValues(Arrays.asList("one", "101"))
					.setRowId(1L).setVersionNumber(4L).setEtag("fake-etag-1"));
			handler.nextRow(new Row().setValues(Arrays.asList("two", "202"))
					.setRowId(2L).setVersionNumber(5L).setEtag("fake-etag-2"));
			handler.nextRow(new Row().setValues(Arrays.asList("three", "303"))
					.setRowId(3L).setVersionNumber(6L).setEtag("fake-etag-3"));
		}

		// Verify the file was written and uploaded to S3
		verify(mockSnapshotStore).saveSnapshot(eq(sessionId), any(), anyString(), eq(createdByUserId));

		// Decode the snapshot and verify structure
		assertNotNull(capturedBytes[0], "Snapshot bytes should have been captured");

		try (IndexedModelDecoder decoder = new IndexedModelDecoder(() -> new ByteArrayInputStream(capturedBytes[0]))) {
			// Verify clock table exists
			assertNotNull(decoder.getClockTable(), "Clock table should not be null");

			// Verify root node ID
			LogicalTimestamp rootId = decoder.getRootNodeId();
			assertNotNull(rootId, "Root node ID should not be null");

			// Collect all nodes
			List<Node> nodes = StreamSupport.stream(decoder.spliterator(), false)
					.collect(Collectors.toList());

			assertTrue(nodes.size() > 0, "Should have at least one node");

			// Find the root object node
			ValueNode rootValue = findNodeById(nodes, decoder.getRootNodeId(), ValueNode.class);
			assertEquals(0, rootId.getReplicaId(), "Root ValueNode should have correct replica ID");

			ObjectNode rootObject = findNodeById(nodes, rootValue.getValue(), ObjectNode.class);
			assertNotNull(rootObject, "Root object should exist");
			assertEquals(replicaId, rootObject.getId().getReplicaId(), "Root ObjectNode should have correct replica ID");

			// Verify root object has expected fields
			assertTrue(rootObject.getValue().containsKey("doc_version"), "Root should have doc_version");
			assertTrue(rootObject.getValue().containsKey("columnNames"), "Root should have columnNames");
			assertTrue(rootObject.getValue().containsKey("columnOrder"), "Root should have columnOrder");
			assertTrue(rootObject.getValue().containsKey("rows"), "Root should have rows");

			// Verify rows array
			ArrayNode rowsArray = findNodeById(nodes, rootObject.getValue().get("rows"), ArrayNode.class);
			assertNotNull(rowsArray, "Rows array should exist");
			assertEquals(3, rowsArray.getElements().size(), "Should have 3 rows");

			// Verify column names
			VectorNode columnNames = findNodeById(nodes, rootObject.getValue().get("columnNames"), VectorNode.class);
			assertNotNull(columnNames, "Column names should exist");
			assertEquals(2, columnNames.getValues().size(), "Should have 2 columns");
		}
	}

	/**
	 * Test that verifies the complete snapshot structure with row data and metadata.
	 */
	@Test
	public void testSnapshotStructureWithRowData() throws IOException {
		final byte[][] capturedBytes = new byte[1][];

		when(mockS3Client.uploadPart(any(UploadPartRequest.class))).thenAnswer(invocation -> {
			UploadPartRequest request = invocation.getArgument(0);
			if (capturedBytes[0] == null) {
				capturedBytes[0] = Files.readAllBytes(request.getFile().toPath());
			}
			UploadPartResult result = new UploadPartResult();
			result.setETag("etag-1");
			result.setPartNumber(1);
			return result;
		});

		// Write snapshot with specific data
		try (SnapshotRowHandler handler = new SnapshotRowHandler(mockSnapshotStore, sessionId, replicaId, schema,
				requiredColumnIndices, mockFileProvider, mockS3Client, mockConfig, createdByUserId, mockValidationManager, null)) {
			handler.nextRow(new Row().setValues(Arrays.asList("testValue", "42"))
					.setRowId(100L).setVersionNumber(1L).setEtag("test-etag"));
		}

		// Decode and verify row data
		try (IndexedModelDecoder decoder = new IndexedModelDecoder(() -> new ByteArrayInputStream(capturedBytes[0]))) {
			List<Node> nodes = StreamSupport.stream(decoder.spliterator(), false)
					.collect(Collectors.toList());

			ValueNode rootValue = findNodeById(nodes, decoder.getRootNodeId(), ValueNode.class);
			ObjectNode rootObject = findNodeById(nodes, rootValue.getValue(), ObjectNode.class);
			ArrayNode rowsArray = findNodeById(nodes, rootObject.getValue().get("rows"), ArrayNode.class);

			// Get the first row
			ObjectNode firstRow = findNodeById(nodes, rowsArray.getElements().get(0).getDataId(), ObjectNode.class);
			assertNotNull(firstRow, "First row object should exist");

			// Verify row has data field
			assertTrue(firstRow.getValue().containsKey("data"), "Row should have data field");

			// Verify row has metadata field
			assertTrue(firstRow.getValue().containsKey("metadata"), "Row should have metadata field");

			// Verify row data
			VectorNode rowData = findNodeById(nodes, firstRow.getValue().get("data"), VectorNode.class);
			assertNotNull(rowData, "Row data should exist");
			assertEquals(2, rowData.getValues().size(), "Row should have 2 cell values");
		}
	}

	/**
	 * Test empty snapshot (no rows) can be decoded properly.
	 */
	@Test
	public void testEmptySnapshotStructure() throws IOException {
		final byte[][] capturedBytes = new byte[1][];

		when(mockS3Client.uploadPart(any(UploadPartRequest.class))).thenAnswer(invocation -> {
			UploadPartRequest request = invocation.getArgument(0);
			if (capturedBytes[0] == null) {
				capturedBytes[0] = Files.readAllBytes(request.getFile().toPath());
			}
			UploadPartResult result = new UploadPartResult();
			result.setETag("etag-1");
			result.setPartNumber(1);
			return result;
		});

		// Write empty snapshot
		try (SnapshotRowHandler handler = new SnapshotRowHandler(mockSnapshotStore, sessionId, replicaId, schema,
				requiredColumnIndices, mockFileProvider, mockS3Client, mockConfig, createdByUserId, mockValidationManager, null)) {
			// No rows added
		}

		// Verify snapshot was saved even with no rows
		verify(mockSnapshotStore).saveSnapshot(eq(sessionId), any(), anyString(), eq(createdByUserId));

		// Decode and verify empty snapshot
		try (IndexedModelDecoder decoder = new IndexedModelDecoder(() -> new ByteArrayInputStream(capturedBytes[0]))) {
			List<Node> nodes = StreamSupport.stream(decoder.spliterator(), false)
					.collect(Collectors.toList());

			ValueNode rootValue = findNodeById(nodes, decoder.getRootNodeId(), ValueNode.class);
			ObjectNode rootObject = findNodeById(nodes, rootValue.getValue(), ObjectNode.class);
			ArrayNode rowsArray = findNodeById(nodes, rootObject.getValue().get("rows"), ArrayNode.class);

			assertNotNull(rowsArray, "Rows array should exist even when empty");
			assertTrue(rowsArray.getElements().isEmpty(), "Rows array should be empty");
		}
	}

	/**
	 * Test large snapshot with many rows to verify multipart upload handling and decoding.
	 */
	@Test
	public void testLargeSnapshotFile() throws IOException {
		final byte[][] capturedBytes = new byte[1][];

		when(mockS3Client.uploadPart(any(UploadPartRequest.class))).thenAnswer(invocation -> {
			UploadPartRequest request = invocation.getArgument(0);
			if (capturedBytes[0] == null) {
				capturedBytes[0] = Files.readAllBytes(request.getFile().toPath());
			}
			UploadPartResult result = new UploadPartResult();
			result.setETag("etag-1");
			result.setPartNumber(1);
			return result;
		});

		// Write many rows to create a larger snapshot
		int rowCount = 100; // Use 100 for faster test
		try (SnapshotRowHandler handler = new SnapshotRowHandler(mockSnapshotStore, sessionId, replicaId, schema,
				requiredColumnIndices, mockFileProvider, mockS3Client, mockConfig, createdByUserId, mockValidationManager, null)) {
			for (int i = 0; i < rowCount; i++) {
				handler.nextRow(new Row().setValues(Arrays.asList("value" + i, String.valueOf(i)))
						.setRowId((long) i).setVersionNumber((long) i + 1).setEtag("etag-" + i));
			}
		}

		// Verify snapshot was saved
		verify(mockSnapshotStore).saveSnapshot(eq(sessionId), any(), anyString(), eq(createdByUserId));

		// Decode and verify all rows are present
		try (IndexedModelDecoder decoder = new IndexedModelDecoder(() -> new ByteArrayInputStream(capturedBytes[0]))) {
			List<Node> nodes = StreamSupport.stream(decoder.spliterator(), false)
					.collect(Collectors.toList());

			ValueNode rootValue = findNodeById(nodes, decoder.getRootNodeId(), ValueNode.class);
			ObjectNode rootObject = findNodeById(nodes, rootValue.getValue(), ObjectNode.class);
			ArrayNode rowsArray = findNodeById(nodes, rootObject.getValue().get("rows"), ArrayNode.class);

			assertNotNull(rowsArray, "Rows array should exist");
			assertEquals(rowCount, rowsArray.getElements().size(), "Should have " + rowCount + " rows");
		}
	}

	/**
	 * Helper method to find a node by its ID from the list of decoded nodes.
	 */
	@SuppressWarnings("unchecked")
	private <T extends Node> T findNodeById(List<Node> nodes, LogicalTimestamp nodeId, Class<T> expectedType) {
		for (Node node : nodes) {
			if (node.getId().equals(nodeId) && expectedType.isInstance(node)) {
				return (T) node;
			}
		}
		return null;
	}

}
