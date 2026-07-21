package org.sagebionetworks.repo.manager.agent.specialist.entitymetadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.manager.agent.CodeInterpreterFileManager;
import org.sagebionetworks.repo.manager.agent.CodeInterpreterFileManager.PushFileRequest;
import org.sagebionetworks.repo.manager.agent.CodeInterpreterFileManager.PushFileResult;
import org.sagebionetworks.repo.manager.agent.specialist.ToolResponse;
import org.sagebionetworks.repo.manager.agent.specialist.entitymetadata.EntityMetadataSpecialistTools.FileToAdd;
import org.sagebionetworks.repo.model.Entity;
import org.sagebionetworks.repo.model.EntityChildrenRequest;
import org.sagebionetworks.repo.model.EntityChildrenResponse;
import org.sagebionetworks.repo.model.FileEntity;
import org.sagebionetworks.repo.model.Folder;
import org.sagebionetworks.repo.model.Project;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.annotation.v2.Annotations;
import org.sagebionetworks.repo.model.annotation.v2.AnnotationsV2TestUtils;
import org.sagebionetworks.repo.model.annotation.v2.AnnotationsValueType;
import org.sagebionetworks.repo.model.file.FileHandleAssociateType;
import org.sagebionetworks.repo.model.file.FileHandleAssociation;
import org.sagebionetworks.repo.model.schema.JsonSchemaObjectBinding;
import org.sagebionetworks.repo.service.EntityService;
import org.springframework.ai.chat.model.ToolContext;

@ExtendWith(MockitoExtension.class)
public class EntityMetadataSpecialistToolsTest {

	@Mock
	private EntityService mockEntityService;

	@Mock
	private CodeInterpreterFileManager mockCodeInterpreterFileManager;

	private EntityMetadataSpecialistTools tools;
	private UserInfo userInfo;
	private ToolContext toolContext;
	private ToolContext toolContextWithSession;

	@BeforeEach
	public void setup() {
		tools = new EntityMetadataSpecialistTools(mockEntityService, mockCodeInterpreterFileManager);
		userInfo = new UserInfo(false, 101L);
		toolContext = new ToolContext(Map.of("userInfo", userInfo));
		toolContextWithSession = new ToolContext(Map.of("userInfo", userInfo, "sessionId", "session-123"));
	}

	@Test
	public void testGetEntityDetailsWithValidId() {
		Project project = new Project();
		project.setId("syn123");
		project.setName("MyProject");
		when(mockEntityService.getEntity(userInfo.getId(), "syn123")).thenReturn(project);

		// call under test
		ToolResponse<Entity> response = tools.getEntityDetails("syn123", toolContext);

		assertNull(response.getErrorMessage());
		assertEquals(project, response.getResponseBody());
		verify(mockEntityService).getEntity(userInfo.getId(), "syn123");
	}

	@Test
	public void testGetEntityDetailsWithVersion() {
		Folder folder = new Folder();
		folder.setId("syn456");
		folder.setName("VersionedFolder");
		when(mockEntityService.getEntityForVersion(userInfo.getId(), "syn456", 3L)).thenReturn(folder);

		// call under test
		ToolResponse<Entity> response = tools.getEntityDetails("syn456.3", toolContext);

		assertNull(response.getErrorMessage());
		assertEquals(folder, response.getResponseBody());
		verify(mockEntityService).getEntityForVersion(userInfo.getId(), "syn456", 3L);
	}

	@Test
	public void testGetEntityDetailsWithNoUserInfo() {
		ToolContext noUserContext = new ToolContext(Map.of());

		// call under test
		ToolResponse<Entity> response = tools.getEntityDetails("syn123", noUserContext);

		assertNull(response.getResponseBody());
		assertEquals("No user context available", response.getErrorMessage());
		verifyNoInteractions(mockEntityService);
	}

	@Test
	public void testGetEntityDetailsWithInvalidId() {
		// call under test
		ToolResponse<Entity> response = tools.getEntityDetails("not_a_valid_id!", toolContext);

		assertNull(response.getResponseBody());
		assertNotNull(response.getErrorMessage());
		verifyNoInteractions(mockEntityService);
	}

	@Test
	public void testGetEntityDetailsWithException() {
		when(mockEntityService.getEntity(userInfo.getId(), "syn123")).thenThrow(new RuntimeException("Not found"));

		// call under test
		ToolResponse<Entity> response = tools.getEntityDetails("syn123", toolContext);

		assertNull(response.getResponseBody());
		assertEquals("Error getting details for entity 'syn123': Not found", response.getErrorMessage());
	}

	@Test
	public void testGetAnnotationsWithValidId() {
		Annotations annotations = new Annotations().setId("syn123").setEtag("etag-1");
		AnnotationsV2TestUtils.putAnnotations(annotations, "sample", "SAMPLE_1", AnnotationsValueType.STRING);
		AnnotationsV2TestUtils.putAnnotations(annotations, "count", "42", AnnotationsValueType.LONG);
		when(mockEntityService.getEntityAnnotations(userInfo.getId(), "syn123", true)).thenReturn(annotations);

		// call under test
		ToolResponse<Annotations> response = tools.getAnnotations("syn123", toolContext);

		assertNull(response.getErrorMessage());
		assertEquals(annotations, response.getResponseBody());
		verify(mockEntityService).getEntityAnnotations(userInfo.getId(), "syn123", true);
	}

	@Test
	public void testGetAnnotationsWithNoUserInfo() {
		ToolContext noUserContext = new ToolContext(Map.of());

		// call under test
		ToolResponse<Annotations> response = tools.getAnnotations("syn123", noUserContext);

		assertNull(response.getResponseBody());
		assertEquals("No user context available", response.getErrorMessage());
		verifyNoInteractions(mockEntityService);
	}

	@Test
	public void testGetAnnotationsWithException() {
		when(mockEntityService.getEntityAnnotations(userInfo.getId(), "syn123", true))
				.thenThrow(new RuntimeException("Access denied"));

		// call under test
		ToolResponse<Annotations> response = tools.getAnnotations("syn123", toolContext);

		assertNull(response.getResponseBody());
		assertEquals("Error getting annotations for entity 'syn123': Access denied", response.getErrorMessage());
	}

	@Test
	public void testGetSchemaBindingWithValidId() {
		JsonSchemaObjectBinding binding = new JsonSchemaObjectBinding();
		binding.setObjectId(123L);
		when(mockEntityService.getBoundSchema(userInfo.getId(), "syn123")).thenReturn(binding);

		// call under test
		ToolResponse<JsonSchemaObjectBinding> response = tools.getSchemaBinding("syn123", toolContext);

		assertNull(response.getErrorMessage());
		assertEquals(binding, response.getResponseBody());
		verify(mockEntityService).getBoundSchema(userInfo.getId(), "syn123");
	}

	@Test
	public void testGetSchemaBindingWithNoUserInfo() {
		ToolContext noUserContext = new ToolContext(Map.of());

		// call under test
		ToolResponse<JsonSchemaObjectBinding> response = tools.getSchemaBinding("syn123", noUserContext);

		assertNull(response.getResponseBody());
		assertEquals("No user context available", response.getErrorMessage());
		verifyNoInteractions(mockEntityService);
	}

	@Test
	public void testGetChildrenWithValidId() {
		EntityChildrenResponse children = new EntityChildrenResponse().setNextPageToken("next-token");
		when(mockEntityService.getChildren(eq(userInfo.getId()), any(EntityChildrenRequest.class))).thenReturn(children);

		// call under test
		ToolResponse<EntityChildrenResponse> response = tools.getChildren("syn123", null, toolContext);

		assertNull(response.getErrorMessage());
		assertEquals(children, response.getResponseBody());

		ArgumentCaptor<EntityChildrenRequest> requestCaptor = ArgumentCaptor.forClass(EntityChildrenRequest.class);
		verify(mockEntityService).getChildren(eq(userInfo.getId()), requestCaptor.capture());
		assertEquals("syn123", requestCaptor.getValue().getParentId());
	}

	@Test
	public void testGetChildrenWithNextPageToken() {
		EntityChildrenResponse children = new EntityChildrenResponse();
		when(mockEntityService.getChildren(eq(userInfo.getId()), any(EntityChildrenRequest.class))).thenReturn(children);

		// call under test
		tools.getChildren("syn123", "page-2", toolContext);

		ArgumentCaptor<EntityChildrenRequest> requestCaptor = ArgumentCaptor.forClass(EntityChildrenRequest.class);
		verify(mockEntityService).getChildren(eq(userInfo.getId()), requestCaptor.capture());
		assertEquals("page-2", requestCaptor.getValue().getNextPageToken());
	}

	@Test
	public void testGetChildrenWithNoUserInfo() {
		ToolContext noUserContext = new ToolContext(Map.of());

		// call under test
		ToolResponse<EntityChildrenResponse> response = tools.getChildren("syn123", null, noUserContext);

		assertNull(response.getResponseBody());
		assertEquals("No user context available", response.getErrorMessage());
		verifyNoInteractions(mockEntityService);
	}

	@Test
	public void testAddFilesToSessionWithSuccess() {
		when(mockEntityService.getEntity(userInfo.getId(), "syn123"))
				.thenReturn(new FileEntity().setId("syn123").setDataFileHandleId("222"));
		when(mockEntityService.getEntityForVersion(userInfo.getId(), "syn456", 2L))
				.thenReturn(new FileEntity().setId("syn456").setDataFileHandleId("333"));

		PushFileResult result1 = new PushFileResult(
				new PushFileRequest(new FileHandleAssociation().setFileHandleId("222"), "meta/a.csv"), null, null);
		PushFileResult result2 = new PushFileResult(
				new PushFileRequest(new FileHandleAssociation().setFileHandleId("333"), "meta/b.csv"), null, null);
		when(mockCodeInterpreterFileManager.pushFileHandlesToSession(eq(userInfo), any(), eq("session-123")))
				.thenReturn(List.of(result1, result2));

		List<FileToAdd> files = List.of(new FileToAdd("syn123", "meta/a.csv"), new FileToAdd("syn456.2", "meta/b.csv"));

		// call under test
		String response = tools.addFilesToSession(files, toolContextWithSession);

		assertTrue(response.contains("Added 'syn123' at 'meta/a.csv'"));
		assertTrue(response.contains("Added 'syn456.2' at 'meta/b.csv'"));

		ArgumentCaptor<List<PushFileRequest>> pushCaptor = ArgumentCaptor.forClass(List.class);
		verify(mockCodeInterpreterFileManager).pushFileHandlesToSession(eq(userInfo), pushCaptor.capture(), eq("session-123"));
		List<PushFileRequest> pushRequests = pushCaptor.getValue();
		assertEquals(2, pushRequests.size());
		assertEquals("222", pushRequests.get(0).association().getFileHandleId());
		assertEquals(FileHandleAssociateType.FileEntity, pushRequests.get(0).association().getAssociateObjectType());
		assertEquals("syn123", pushRequests.get(0).association().getAssociateObjectId());
		assertEquals("meta/a.csv", pushRequests.get(0).sessionPath());
		assertEquals("333", pushRequests.get(1).association().getFileHandleId());
	}

	@Test
	public void testAddFilesToSessionWithFailure() {
		when(mockEntityService.getEntity(userInfo.getId(), "syn123"))
				.thenReturn(new FileEntity().setId("syn123").setDataFileHandleId("222"));

		PushFileResult failure = new PushFileResult(
				new PushFileRequest(new FileHandleAssociation().setFileHandleId("222"), "meta/a.csv"), null, "UNAUTHORIZED");
		when(mockCodeInterpreterFileManager.pushFileHandlesToSession(eq(userInfo), any(), eq("session-123")))
				.thenReturn(List.of(failure));

		// call under test
		String response = tools.addFilesToSession(List.of(new FileToAdd("syn123", "meta/a.csv")), toolContextWithSession);

		assertTrue(response.contains("Failed to add 'syn123': UNAUTHORIZED"));
	}

	@Test
	public void testAddFilesToSessionWithNonFileEntity() {
		// A Project is not a FileEntity: it should be reported as a failure without a batch push.
		when(mockEntityService.getEntity(userInfo.getId(), "syn123"))
				.thenReturn(new Project().setId("syn123"));

		// call under test
		String response = tools.addFilesToSession(List.of(new FileToAdd("syn123", "meta/a.csv")), toolContextWithSession);

		assertEquals("Failed to add 'syn123': not a FileEntity", response);
		verifyNoInteractions(mockCodeInterpreterFileManager);
	}

	@Test
	public void testAddFilesToSessionWithMixOfFileAndNonFileEntity() {
		// syn123 is a FileEntity and should be pushed; syn999 is a Project and should fail locally,
		// without aborting the push of the valid file.
		when(mockEntityService.getEntity(userInfo.getId(), "syn123"))
				.thenReturn(new FileEntity().setId("syn123").setDataFileHandleId("222"));
		when(mockEntityService.getEntity(userInfo.getId(), "syn999"))
				.thenReturn(new Project().setId("syn999"));

		PushFileResult staged = new PushFileResult(
				new PushFileRequest(new FileHandleAssociation().setFileHandleId("222"), "meta/a.csv"), null, null);
		when(mockCodeInterpreterFileManager.pushFileHandlesToSession(eq(userInfo), any(), eq("session-123")))
				.thenReturn(List.of(staged));

		List<FileToAdd> files = List.of(new FileToAdd("syn123", "meta/a.csv"), new FileToAdd("syn999", "meta/b.csv"));

		// call under test
		String response = tools.addFilesToSession(files, toolContextWithSession);

		assertTrue(response.contains("Added 'syn123' at 'meta/a.csv'"), "Got: " + response);
		assertTrue(response.contains("Failed to add 'syn999': not a FileEntity"), "Got: " + response);

		// Only the valid FileEntity is forwarded to the batch push.
		ArgumentCaptor<List<PushFileRequest>> pushCaptor = ArgumentCaptor.forClass(List.class);
		verify(mockCodeInterpreterFileManager).pushFileHandlesToSession(eq(userInfo), pushCaptor.capture(), eq("session-123"));
		assertEquals(1, pushCaptor.getValue().size());
		assertEquals("222", pushCaptor.getValue().get(0).association().getFileHandleId());
	}

	@Test
	public void testAddFilesToSessionWithNoUserInfo() {
		ToolContext noUserContext = new ToolContext(Map.of("sessionId", "session-123"));

		// call under test
		String response = tools.addFilesToSession(List.of(new FileToAdd("syn123", "meta/a.csv")), noUserContext);

		assertEquals("Error: No user context available", response);
		verifyNoInteractions(mockCodeInterpreterFileManager);
		verifyNoInteractions(mockEntityService);
	}

	@Test
	public void testAddFilesToSessionWithNoSessionId() {
		// call under test
		String response = tools.addFilesToSession(List.of(new FileToAdd("syn123", "meta/a.csv")), toolContext);

		assertEquals("Error: No code interpreter session ID available", response);
		verifyNoInteractions(mockCodeInterpreterFileManager);
		verifyNoInteractions(mockEntityService);
	}

	@Test
	public void testAddFilesToSessionWithEmptyFiles() {
		// call under test
		String response = tools.addFilesToSession(List.of(), toolContextWithSession);

		assertEquals("Error: No files were provided to add to the session", response);
		verifyNoInteractions(mockCodeInterpreterFileManager);
		verifyNoInteractions(mockEntityService);
	}
}
