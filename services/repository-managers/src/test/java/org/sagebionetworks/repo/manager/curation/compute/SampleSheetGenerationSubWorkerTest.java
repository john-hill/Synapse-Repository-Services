package org.sagebionetworks.repo.manager.curation.compute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.manager.EntityManager;
import org.sagebionetworks.repo.manager.agent.CodeInterpreterFileManager;
import org.sagebionetworks.repo.manager.agent.supervisor.SampleSheetSupervisor;
import org.sagebionetworks.repo.manager.agent.supervisor.SampleSheetSupervisorFactory;
import org.sagebionetworks.repo.manager.curation.CurationTaskManager;
import org.sagebionetworks.repo.model.RecordSet;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.curation.CurationTask;
import org.sagebionetworks.repo.model.curation.execution.SampleSheetGenerationExecutionDetails;
import org.sagebionetworks.repo.model.curation.execution.SampleSheetGenerationExecutionProperties;
import org.sagebionetworks.repo.model.curation.metadata.FileBasedMetadataTaskProperties;
import org.sagebionetworks.repo.model.curation.metadata.RecordBasedMetadataTaskProperties;
import org.sagebionetworks.repo.model.dao.asynch.AsyncJobProgressCallback;
import org.sagebionetworks.repo.model.entity.BindSchemaToEntityRequest;
import org.springaicommunity.agentcore.codeinterpreter.AgentCoreCodeInterpreterClient;

@ExtendWith(MockitoExtension.class)
public class SampleSheetGenerationSubWorkerTest {

	@Mock
	private SampleSheetSupervisorFactory supervisorFactory;
	@Mock
	private SampleSheetSupervisor supervisor;
	@Mock
	private AgentCoreCodeInterpreterClient codeInterpreterClient;
	@Mock
	private CodeInterpreterFileManager codeInterpreterFileManager;
	@Mock
	private EntityManager entityManager;
	@Mock
	private CurationTaskManager curationTaskManager;
	@Mock
	private AsyncJobProgressCallback callback;

	private SampleSheetGenerationSubWorker subWorker;

	private UserInfo user;
	private CurationTask task;
	private SampleSheetGenerationExecutionDetails details;

	@BeforeEach
	public void setup() {
		subWorker = new SampleSheetGenerationSubWorker(supervisorFactory, codeInterpreterClient, codeInterpreterFileManager,
				entityManager, curationTaskManager);
		user = new UserInfo(false, 101L);
		// The generation task carries its input parameters in its SampleSheetGenerationExecutionProperties.
		task = new CurationTask().setTaskId(555L).setProjectId("syn1").setDataType("fastq")
				.setTaskProperties(new SampleSheetGenerationExecutionProperties()
						.setInputTaskId(777L)
						.setTargetSchemaId("my.org-Sheet-1.0.0")
						.setDestinationTaskId(888L));
		details = new SampleSheetGenerationExecutionDetails();
	}

	/**
	 * Stubs the input file-based task (777) and destination record-based task (888) referenced by the
	 * generation task's properties.
	 */
	private void stubInputAndDestinationTasks(String fileViewId, String recordSetId) {
		when(curationTaskManager.getCurationTask(user, 777L)).thenReturn(new CurationTask().setTaskId(777L)
				.setProjectId("syn1").setTaskProperties(new FileBasedMetadataTaskProperties().setFileViewId(fileViewId)));
		when(curationTaskManager.getCurationTask(user, 888L)).thenReturn(new CurationTask().setTaskId(888L)
				.setProjectId("syn1").setTaskProperties(new RecordBasedMetadataTaskProperties().setRecordSetId(recordSetId)));
	}

	@Test
	public void testGetExecutionDetailsType() {
		// call under test
		assertEquals(SampleSheetGenerationExecutionDetails.class, subWorker.getExecutionDetailsType());
	}

	@Test
	public void testExecuteHappyPath() throws Exception {
		stubInputAndDestinationTasks("syn100", "syn300");
		when(codeInterpreterClient.startSession(any())).thenReturn("session-1");
		when(supervisorFactory.create()).thenReturn(supervisor);
		when(supervisor.chat(any(), eq(user), eq("session-1")))
				.thenReturn("Done. RESULT: SUCCESS - " + SampleSheetGenerationSubWorker.OUTPUT_CSV_PATH);
		when(codeInterpreterFileManager.getFileFromSession(eq(user), eq("session-1"),
				eq(SampleSheetGenerationSubWorker.OUTPUT_CSV_PATH), eq("text/csv"))).thenReturn("999");
		when(entityManager.getEntity(user, "syn300", RecordSet.class)).thenReturn(new RecordSet()
				.setId("syn300").setParentId("syn200").setUpsertKey(List.of("sampleId"))
				.setVersionLabel("1").setVersionComment("original"));

		// call under test
		SampleSheetGenerationExecutionDetails result = subWorker.execute(user, task, details, callback);

		assertEquals(details, result);

		// The source FileView from the input task is passed to the supervisor.
		ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
		verify(supervisor).chat(messageCaptor.capture(), eq(user), eq("session-1"));
		assertTrue(messageCaptor.getValue().contains("inputFileViewId: syn100"), "Got: " + messageCaptor.getValue());

		// The generated CSV is stored as a new version of the existing RecordSet, preserving its properties.
		ArgumentCaptor<RecordSet> recordSetCaptor = ArgumentCaptor.forClass(RecordSet.class);
		verify(entityManager).updateEntity(eq(user), recordSetCaptor.capture(), eq(true), isNull());
		RecordSet updated = recordSetCaptor.getValue();
		assertEquals("syn300", updated.getId());
		assertEquals("999", updated.getDataFileHandleId());
		assertEquals("syn200", updated.getParentId());
		assertEquals(List.of("sampleId"), updated.getUpsertKey());
		// Version label/comment cleared so the DAO assigns a unique label for the new version.
		assertNull(updated.getVersionLabel());
		assertNull(updated.getVersionComment());

		// Target schema bound to the RecordSet.
		ArgumentCaptor<BindSchemaToEntityRequest> bindCaptor = ArgumentCaptor.forClass(BindSchemaToEntityRequest.class);
		verify(entityManager).bindSchemaToEntity(eq(user), bindCaptor.capture());
		assertEquals("syn300", bindCaptor.getValue().getEntityId());
		assertEquals("my.org-Sheet-1.0.0", bindCaptor.getValue().getSchema$id());

		// No new RecordSet is created and the referenced tasks are left untouched.
		verify(entityManager, never()).createEntity(any(), any(), any());
		verify(curationTaskManager, never()).updateCurationTask(any(), any());

		// Session is always stopped.
		verify(codeInterpreterClient).stopSession("session-1");
	}

	@Test
	public void testExecuteWithMissingExecutionProperties() {
		task.setTaskProperties(new FileBasedMetadataTaskProperties());

		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> subWorker.execute(user, task, details, callback));

		assertTrue(ex.getMessage().contains("SampleSheetGenerationExecutionProperties"));
		verify(codeInterpreterClient, never()).startSession(any());
	}

	@Test
	public void testExecuteWithNonFileBasedInputTask() {
		// The input task is not a file-based task; this fails before the supervisor runs.
		when(curationTaskManager.getCurationTask(user, 777L)).thenReturn(new CurationTask().setTaskId(777L)
				.setProjectId("syn1").setTaskProperties(new RecordBasedMetadataTaskProperties()));

		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> subWorker.execute(user, task, details, callback));

		assertTrue(ex.getMessage().contains("must be a file-based metadata task"));
		verify(codeInterpreterClient, never()).startSession(any());
	}

	@Test
	public void testExecuteWithNonRecordBasedDestinationTask() {
		when(curationTaskManager.getCurationTask(user, 777L)).thenReturn(new CurationTask().setTaskId(777L)
				.setProjectId("syn1").setTaskProperties(new FileBasedMetadataTaskProperties().setFileViewId("syn100")));
		// The destination task is not a record-based task.
		when(curationTaskManager.getCurationTask(user, 888L)).thenReturn(new CurationTask().setTaskId(888L)
				.setProjectId("syn1").setTaskProperties(new FileBasedMetadataTaskProperties()));

		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> subWorker.execute(user, task, details, callback));

		assertTrue(ex.getMessage().contains("must be a record-based metadata task"));
		verify(codeInterpreterClient, never()).startSession(any());
	}

	@Test
	public void testExecuteWithDestinationTaskMissingRecordSet() {
		when(curationTaskManager.getCurationTask(user, 777L)).thenReturn(new CurationTask().setTaskId(777L)
				.setProjectId("syn1").setTaskProperties(new FileBasedMetadataTaskProperties().setFileViewId("syn100")));
		// The destination task is record-based but has no RecordSet configured.
		when(curationTaskManager.getCurationTask(user, 888L)).thenReturn(new CurationTask().setTaskId(888L)
				.setProjectId("syn1").setTaskProperties(new RecordBasedMetadataTaskProperties()));

		// call under test
		assertThrows(IllegalArgumentException.class, () -> subWorker.execute(user, task, details, callback));

		verify(codeInterpreterClient, never()).startSession(any());
	}

	@Test
	public void testExecuteWithSupervisorError() throws Exception {
		stubInputAndDestinationTasks("syn100", "syn300");
		when(codeInterpreterClient.startSession(any())).thenReturn("session-1");
		when(supervisorFactory.create()).thenReturn(supervisor);
		when(supervisor.chat(any(), eq(user), eq("session-1")))
				.thenReturn("RESULT: ERROR - the input view syn100 is empty");

		// call under test
		IllegalStateException ex = assertThrows(IllegalStateException.class,
				() -> subWorker.execute(user, task, details, callback));

		assertTrue(ex.getMessage().contains("input view syn100 is empty"));
		// No persistence should occur when the supervisor did not succeed.
		verify(entityManager, never()).updateEntity(any(), any(), eq(true), any());
		verify(entityManager, never()).bindSchemaToEntity(any(), any());
		// Session is still stopped.
		verify(codeInterpreterClient).stopSession("session-1");
	}

	@Test
	public void testExecuteWithMissingInputTaskId() {
		((SampleSheetGenerationExecutionProperties) task.getTaskProperties()).setInputTaskId(null);

		// call under test
		assertThrows(IllegalArgumentException.class, () -> subWorker.execute(user, task, details, callback));
	}

	@Test
	public void testExecuteWithMissingDestinationTaskId() {
		((SampleSheetGenerationExecutionProperties) task.getTaskProperties()).setDestinationTaskId(null);

		// call under test
		assertThrows(IllegalArgumentException.class, () -> subWorker.execute(user, task, details, callback));
	}
}
