package org.sagebionetworks.repo.manager.grid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.StackConfiguration;
import org.sagebionetworks.aws.SynapseS3Client;
import org.sagebionetworks.repo.model.dbo.dao.table.TableModelTestUtils;
import org.sagebionetworks.repo.model.grid.ClockTable;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.repo.model.table.Row;
import org.sagebionetworks.util.FileProvider;

import com.amazonaws.services.s3.model.AbortMultipartUploadRequest;
import com.amazonaws.services.s3.model.CompleteMultipartUploadRequest;
import com.amazonaws.services.s3.model.CompleteMultipartUploadResult;
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadResult;
import com.amazonaws.services.s3.model.UploadPartRequest;
import com.amazonaws.services.s3.model.UploadPartResult;

@ExtendWith(MockitoExtension.class)
public class SnapshotRowHandlerTest {

	@Mock
	private SnapshotStore mockSnapshotStore;

	@Mock
	private FileProvider mockFileProvider;

	@Mock
	private SynapseS3Client mockS3Client;

	@Mock
	private StackConfiguration mockConfig;

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
		schema = List.of(new ColumnModel().setColumnType(ColumnType.STRING).setName("aString"),
				new ColumnModel().setColumnType(ColumnType.INTEGER).setName("anInt"));
		requiredColumnIndices = Collections.emptyList();
		createdByUserId = 999L;
		stackName = "dev";

		// Create a real temp file for testing
		tempFile = new File(tempDir, "snapshot-test.cbor");

		lenient().when(mockConfig.getStack()).thenReturn(stackName);
		lenient().when(mockFileProvider.createTempFile("snapshot", ".cbor")).thenReturn(tempFile);
	}

	@Test
	public void testNoColumnsNoRows() throws IOException {
		schema = Collections.emptyList();
		setupS3Mocks();

		// call under test
		try (SnapshotRowHandler handler = new SnapshotRowHandler(mockSnapshotStore, sessionId, replicaId, schema,
				requiredColumnIndices, mockFileProvider, mockS3Client, mockConfig, createdByUserId)) {
			// no row to add
		}

		verifyFileCreatedUploadedAndDeleted();
		verifySnapshotSaved();
	}

	@Test
	public void testWithColumnNoRows() throws IOException {
		setupS3Mocks();

		// call under test
		try (SnapshotRowHandler handler = new SnapshotRowHandler(mockSnapshotStore, sessionId, replicaId, schema,
				requiredColumnIndices, mockFileProvider, mockS3Client, mockConfig, createdByUserId)) {
			// no row to add
		}

		verifyFileCreatedUploadedAndDeleted();
		verifySnapshotSaved();
	}

	@Test
	public void testWithRows() throws IOException {
		setupS3Mocks();

		// call under test
		try (SnapshotRowHandler handler = new SnapshotRowHandler(mockSnapshotStore, sessionId, replicaId, schema,
				requiredColumnIndices, mockFileProvider, mockS3Client, mockConfig, createdByUserId)) {
			handler.nextRow(
					new Row().setValues(Arrays.asList("one", "101")).setRowId(1L).setVersionNumber(4L).setEtag("fake-etag-1"));
			handler.nextRow(
					new Row().setValues(Arrays.asList("two", "202")).setRowId(2L).setVersionNumber(5L).setEtag("fake-etag-2"));
			handler.nextRow(new Row().setValues(Arrays.asList("three", "303")).setRowId(3L).setVersionNumber(6L)
					.setEtag("fake-etag-3"));
		}

		verifyFileCreatedUploadedAndDeleted();
		verifySnapshotSaved();
	}

	@Test
	public void testWithRowsPartialMetadata() throws IOException {
		setupS3Mocks();

		// call under test
		try (SnapshotRowHandler handler = new SnapshotRowHandler(mockSnapshotStore, sessionId, replicaId, schema,
				requiredColumnIndices, mockFileProvider, mockS3Client, mockConfig, createdByUserId)) {
			// Row with partial metadata
			handler.nextRow(new Row().setValues(Arrays.asList("four", "404")).setRowId(3L).setEtag("fake-etag-4"));
			// Row with no metadata
			handler.nextRow(new Row().setValues(Arrays.asList("five", "505")));
		}

		verifyFileCreatedUploadedAndDeleted();
		verifySnapshotSaved();
	}

	@Test
	public void testEachType() throws IOException {
		setupS3Mocks();

		boolean hasDefault = false;
		schema = TableModelTestUtils.createOneOfEachType(hasDefault);
		List<Row> rows = TableModelTestUtils.createRows(schema, 3,
				new TableModelTestUtils.ValueOptions().includeSpace(false));

		// call under test
		try (SnapshotRowHandler handler = new SnapshotRowHandler(mockSnapshotStore, sessionId, replicaId, schema,
				requiredColumnIndices, mockFileProvider, mockS3Client, mockConfig, createdByUserId)) {
			rows.forEach(r -> {
				handler.nextRow(r);
			});
		}

		verifyFileCreatedUploadedAndDeleted();
		verifySnapshotSaved();
	}

	@Test
	public void testWriteNullOrUndefinedUsingRequiredColumnIndices() throws Exception {
		setupS3Mocks();

		requiredColumnIndices = List.of(1); // only the second column is required

		// call under test
		try (SnapshotRowHandler handler = new SnapshotRowHandler(mockSnapshotStore, sessionId, replicaId, schema,
				requiredColumnIndices, mockFileProvider, mockS3Client, mockConfig, createdByUserId)) {
			handler.nextRow(
					new Row().setValues(Arrays.asList(null, null)).setRowId(1L).setVersionNumber(4L).setEtag("fake-etag-1"));
		}

		verifyFileCreatedUploadedAndDeleted();
		verifySnapshotSaved();
	}

	@Test
	public void testS3UploadBucketName() throws IOException {
		setupS3Mocks();

		// call under test
		try (SnapshotRowHandler handler = new SnapshotRowHandler(mockSnapshotStore, sessionId, replicaId, schema,
				requiredColumnIndices, mockFileProvider, mockS3Client, mockConfig, createdByUserId)) {
			handler.nextRow(new Row().setValues(Arrays.asList("one", "101")));
		}

		// Verify S3 bucket name
		ArgumentCaptor<InitiateMultipartUploadRequest> requestCaptor = ArgumentCaptor
				.forClass(InitiateMultipartUploadRequest.class);
		verify(mockS3Client).initiateMultipartUpload(requestCaptor.capture());

		String expectedBucket = stackName + ".grid.snapshot.sagebase.org";
		assertEquals(expectedBucket, requestCaptor.getValue().getBucketName());
	}

	@Test
	public void testS3KeyFormat() throws IOException {
		setupS3Mocks();

		// call under test
		try (SnapshotRowHandler handler = new SnapshotRowHandler(mockSnapshotStore, sessionId, replicaId, schema,
				requiredColumnIndices, mockFileProvider, mockS3Client, mockConfig, createdByUserId)) {
			handler.nextRow(new Row().setValues(Arrays.asList("one", "101")));
		}

		// Verify S3 key format
		ArgumentCaptor<InitiateMultipartUploadRequest> requestCaptor = ArgumentCaptor
				.forClass(InitiateMultipartUploadRequest.class);
		verify(mockS3Client).initiateMultipartUpload(requestCaptor.capture());

		String s3Key = requestCaptor.getValue().getKey();
		assertTrue(s3Key.startsWith("snapshot/" + sessionId + "/"), "S3 key should start with snapshot/{sessionId}/");
		assertTrue(s3Key.endsWith(".cbor"), "S3 key should end with .cbor");
	}

	@Test
	public void testConstructorWithNullSnapshotStore() {
		// call under test
		assertThrows(IllegalArgumentException.class, () -> {
			new SnapshotRowHandler(null, sessionId, replicaId, schema, requiredColumnIndices, mockFileProvider,
					mockS3Client, mockConfig, createdByUserId);
		});
	}

	@Test
	public void testConstructorFailsToCreateTempFile() throws IOException {
		when(mockFileProvider.createTempFile("snapshot", ".cbor")).thenThrow(new IOException("Failed to create temp file"));

		// call under test
		RuntimeException ex = assertThrows(RuntimeException.class, () -> {
			new SnapshotRowHandler(mockSnapshotStore, sessionId, replicaId, schema, requiredColumnIndices,
					mockFileProvider, mockS3Client, mockConfig, createdByUserId);
		});

		assertEquals("Failed to create temporary file for snapshot", ex.getMessage());
	}

	@Test
	public void testCloseWithS3InitiateUploadFailure() throws IOException {
		when(mockS3Client.initiateMultipartUpload(any(InitiateMultipartUploadRequest.class)))
				.thenThrow(new RuntimeException("S3 initiate upload failed"));

		SnapshotRowHandler handler = new SnapshotRowHandler(mockSnapshotStore, sessionId, replicaId, schema,
				requiredColumnIndices, mockFileProvider, mockS3Client, mockConfig, createdByUserId);
		handler.nextRow(new Row().setValues(Arrays.asList("one", "101")));

		// call under test - close should propagate exception
		RuntimeException ex = assertThrows(RuntimeException.class, () -> {
			handler.close();
		});

		assertTrue(ex.getMessage().contains("S3 initiate upload failed"));

		// Verify temp file is still cleaned up even on failure
		assertTrue(!tempFile.exists(), "Temp file should be deleted even after S3 failure");

		// Verify snapshot was NOT saved since upload failed
		verify(mockSnapshotStore, never()).saveSnapshot(any(), any(), any(), any());
	}

	@Test
	public void testUploadPartFailureCallsAbortMultipartUpload() throws IOException {
		String uploadId = "test-upload-id";
		String s3Key = "snapshot/test/file.cbor";

		// Successfully initiate upload
		InitiateMultipartUploadResult initiateResult = new InitiateMultipartUploadResult();
		initiateResult.setUploadId(uploadId);
		when(mockS3Client.initiateMultipartUpload(any(InitiateMultipartUploadRequest.class)))
				.thenReturn(initiateResult);

		// Fail during uploadPart
		when(mockS3Client.uploadPart(any(UploadPartRequest.class)))
				.thenThrow(new RuntimeException("Upload part failed"));

		SnapshotRowHandler handler = new SnapshotRowHandler(mockSnapshotStore, sessionId, replicaId, schema,
				requiredColumnIndices, mockFileProvider, mockS3Client, mockConfig, createdByUserId);
		handler.nextRow(new Row().setValues(Arrays.asList("one", "101")));

		// call under test - close should propagate exception
		RuntimeException ex = assertThrows(RuntimeException.class, () -> {
			handler.close();
		});

		assertTrue(ex.getMessage().contains("Failed to upload snapshot to S3"),
				"Exception message should indicate S3 upload failure");

		// Verify abortMultipartUpload was called with correct parameters
		ArgumentCaptor<AbortMultipartUploadRequest> abortCaptor = ArgumentCaptor
				.forClass(AbortMultipartUploadRequest.class);
		verify(mockS3Client).abortMultipartUpload(abortCaptor.capture());

		AbortMultipartUploadRequest abortRequest = abortCaptor.getValue();
		assertEquals(uploadId, abortRequest.getUploadId(), "Should abort with correct upload ID");
		assertEquals(stackName + ".grid.snapshot.sagebase.org", abortRequest.getBucketName(),
				"Should abort with correct bucket name");
		assertTrue(abortRequest.getKey().startsWith("snapshot/" + sessionId + "/"),
				"Should abort with correct S3 key");

		// Verify temp file is still cleaned up even on failure
		assertTrue(!tempFile.exists(), "Temp file should be deleted even after upload failure");

		// Verify snapshot was NOT saved since upload failed
		verify(mockSnapshotStore, never()).saveSnapshot(any(), any(), any(), any());

		// Verify completeMultipartUpload was NOT called
		verify(mockS3Client, never()).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
	}

	/**
	 * Helper to set up S3 mocks for successful upload
	 */
	private void setupS3Mocks() {
		String uploadId = "test-upload-id";
		String s3Key = "snapshot/test/file.cbor";

		InitiateMultipartUploadResult initiateResult = new InitiateMultipartUploadResult();
		initiateResult.setUploadId(uploadId);

		UploadPartResult uploadResult = new UploadPartResult();
		uploadResult.setETag("etag-1");
		uploadResult.setPartNumber(1);

		CompleteMultipartUploadResult completeResult = new CompleteMultipartUploadResult();
		completeResult.setKey(s3Key);
		completeResult.setBucketName(stackName + ".grid.snapshot.sagebase.org");

		when(mockS3Client.initiateMultipartUpload(any(InitiateMultipartUploadRequest.class))).thenReturn(initiateResult);
		when(mockS3Client.uploadPart(any(UploadPartRequest.class))).thenReturn(uploadResult);
		when(mockS3Client.completeMultipartUpload(any(CompleteMultipartUploadRequest.class))).thenReturn(completeResult);
	}

	private void verifyFileCreatedUploadedAndDeleted() throws IOException {
		verify(mockFileProvider).createTempFile("snapshot", ".cbor");
		verify(mockS3Client).initiateMultipartUpload(any(InitiateMultipartUploadRequest.class));
		verify(mockS3Client).uploadPart(any(UploadPartRequest.class));
		verify(mockS3Client).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
		assertTrue(!tempFile.exists(), "Temp file should be deleted after close");
	}

	private void verifySnapshotSaved() {
		ArgumentCaptor<ClockTable> clockTableCaptor = ArgumentCaptor.forClass(ClockTable.class);
		verify(mockSnapshotStore).saveSnapshot(eq(sessionId), clockTableCaptor.capture(), anyString(), eq(createdByUserId));

		ClockTable clockTable = clockTableCaptor.getValue();
		assertNotNull(clockTable, "Clock table should not be null");
	}

}
