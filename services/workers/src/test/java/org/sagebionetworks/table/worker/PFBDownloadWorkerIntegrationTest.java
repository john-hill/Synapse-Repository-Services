package org.sagebionetworks.table.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.avro.file.SeekableFileInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.AsynchronousJobWorkerHelper;
import org.sagebionetworks.repo.manager.AmazonS3Utility;
import org.sagebionetworks.repo.manager.EntityManager;
import org.sagebionetworks.repo.manager.SemaphoreManager;
import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.manager.asynch.AsynchJobStatusManager;
import org.sagebionetworks.repo.manager.table.ColumnModelManager;
import org.sagebionetworks.repo.model.AuthorizationConstants.BOOTSTRAP_PRINCIPAL;
import org.sagebionetworks.repo.model.Project;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dbo.file.FileHandleDao;
import org.sagebionetworks.repo.model.file.S3FileHandle;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.repo.model.table.DownloadPFBRequest;
import org.sagebionetworks.repo.model.table.DownloadPFBResult;
import org.sagebionetworks.repo.model.table.Row;
import org.sagebionetworks.repo.model.table.RowReference;
import org.sagebionetworks.repo.model.table.RowReferenceSet;
import org.sagebionetworks.repo.model.table.RowReferenceSetResults;
import org.sagebionetworks.repo.model.table.TableEntity;
import org.sagebionetworks.repo.web.NotFoundException;
import org.sagebionetworks.table.cluster.avro.RowPFBReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = { "classpath:test-context.xml" })
public class PFBDownloadWorkerIntegrationTest {
	public static final int MAX_WAIT_MS = 1000 * 60;

	@Autowired
	private AsynchJobStatusManager asynchJobStatusManager;
	@Autowired
	private FileHandleDao fileHandleDao;
	@Autowired
	private EntityManager entityManager;
	@Autowired
	private ColumnModelManager columnManager;
	@Autowired
	private UserManager userManager;
	@Autowired
	private SemaphoreManager semphoreManager;

	@Autowired
	private AsynchronousJobWorkerHelper asyncHelper;

	@Autowired
	private AmazonS3Utility amazonS3Utility;

	private UserInfo adminUserInfo;
	private String tableId;
	private S3FileHandle fileHandle;

	@BeforeEach
	public void before() throws NotFoundException {
		semphoreManager.releaseAllLocksAsAdmin(new UserInfo(true));
		asynchJobStatusManager.emptyAllQueues();
		adminUserInfo = userManager.getUserInfo(BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId());
	}

	@AfterEach
	public void after() {
		entityManager.truncateAll();
		if (fileHandle != null) {
			fileHandleDao.delete(fileHandle.getId());
			amazonS3Utility.deleteFromS3(fileHandle.getKey());
		}

	}

	@Test
	public void testRoundTrip() throws Exception {

		String projectId = entityManager.createEntity(adminUserInfo, new Project().setName("test"), null);
		List<ColumnModel> schema = List.of(new ColumnModel().setName("anInt").setColumnType(ColumnType.INTEGER));
		schema = columnManager.createColumnModels(adminUserInfo, schema);
		List<String> colIds = schema.stream().map(c -> c.getId()).collect(Collectors.toList());

		TableEntity table = asyncHelper.createTable(adminUserInfo, "testTable", projectId, colIds, false);
		List<Row> rows = List.of(new Row().setValues(List.of("9090")));

		RowReferenceSetResults rrsr = asyncHelper.appendRowsToTable(adminUserInfo, schema, table.getId(), rows,
				MAX_WAIT_MS);

		DownloadPFBRequest request = new DownloadPFBRequest();
		request.setSql("select * from " + table.getId());
		request.setPfbEntityName("testing");
		request.setEntityId(tableId);

		// call under test
		DownloadPFBResult result = asyncHelper
				.assertJobResponse(adminUserInfo, request, (DownloadPFBResult response) -> {
					assertNotNull(response);
					assertNotNull(response.getResultsFileHandleId());
				}, MAX_WAIT_MS).getResponse();

		fileHandle = (S3FileHandle) fileHandleDao.get(result.getResultsFileHandleId());
		List<Row> results = readPFBFromS3(fileHandle.getKey());

		List<Row> expected = createExpectedRows(rows, rrsr);

		assertEquals(results, expected);
	}

	/**
	 * Set the row id and version combined with the values from each row to build
	 * and expected Row list.
	 * 
	 * @param rows
	 * @param rrsr
	 * @return
	 */
	List<Row> createExpectedRows(List<Row> rows, RowReferenceSetResults rrsr) {
		List<Row> expected = new ArrayList<>(rows.size());
		List<RowReference> refs = rrsr.getRowReferenceSet().getRows();
		for (int i = 0; i < rows.size(); i++) {
			RowReference ref = refs.get(i);
			Row row = rows.get(i);
			expected.add(new Row().setRowId(ref.getRowId()).setVersionNumber(ref.getVersionNumber())
					.setValues(row.getValues()));
		}
		return expected;
	}

	/**
	 * Helper to download and read PFB avro file from S3.
	 * 
	 * @param key
	 * @return
	 * @throws IOException
	 */
	List<Row> readPFBFromS3(String key) throws IOException {
		File resultFile = amazonS3Utility.downloadFromS3(key);
		try {
			// Read
			List<Row> result = new ArrayList<>();
			try (RowPFBReader reader = new RowPFBReader(new SeekableFileInput(resultFile))) {
				while (reader.hasNext()) {
					result.add(reader.next());
				}
			}
			return result;
		} finally {
			resultFile.delete();
		}
	}

}
