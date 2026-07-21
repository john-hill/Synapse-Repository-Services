package org.sagebionetworks.repo.manager.agent.specialist.entitymetadata;

import java.util.ArrayList;
import java.util.List;

import org.sagebionetworks.repo.manager.agent.CodeInterpreterFileManager;
import org.sagebionetworks.repo.manager.agent.CodeInterpreterFileManager.PushFileRequest;
import org.sagebionetworks.repo.manager.agent.CodeInterpreterFileManager.PushFileResult;
import org.sagebionetworks.repo.manager.agent.specialist.JSONEntityResultConverter;
import org.sagebionetworks.repo.manager.agent.specialist.ToolResponse;
import org.sagebionetworks.repo.model.Entity;
import org.sagebionetworks.repo.model.EntityChildrenRequest;
import org.sagebionetworks.repo.model.EntityChildrenResponse;
import org.sagebionetworks.repo.model.EntityType;
import org.sagebionetworks.repo.model.FileEntity;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.annotation.v2.Annotations;
import org.sagebionetworks.repo.model.entity.Direction;
import org.sagebionetworks.repo.model.entity.IdAndVersion;
import org.sagebionetworks.repo.model.entity.SortBy;
import org.sagebionetworks.repo.model.file.FileHandleAssociateType;
import org.sagebionetworks.repo.model.file.FileHandleAssociation;
import org.sagebionetworks.repo.model.schema.JsonSchemaObjectBinding;
import org.sagebionetworks.repo.service.EntityService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * Tools available to the entity metadata specialist. They retrieve and interpret entity metadata,
 * annotations, and schema information on behalf of the calling user. Reads go through
 * {@link EntityService} (the service layer, which enriches entities with type-specific metadata),
 * and every method operates as the user carried in the {@link ToolContext} so the user's
 * permissions are always respected.
 */
@Service
public class EntityMetadataSpecialistTools {

	private final EntityService entityService;
	private final CodeInterpreterFileManager codeInterpreterFileManager;

	public EntityMetadataSpecialistTools(EntityService entityService,
			CodeInterpreterFileManager codeInterpreterFileManager) {
		this.entityService = entityService;
		this.codeInterpreterFileManager = codeInterpreterFileManager;
	}

	@Tool(description = "Get the details of a Synapse entity including its type, name, parent, and creation "
			+ "information. Use this to answer questions about what an entity is and where it lives.",
			resultConverter = JSONEntityResultConverter.class)
	public ToolResponse<Entity> getEntityDetails(
			@ToolParam(description = "A Synapse entity ID such as 'syn123' or 'syn123.5' for a specific version", required = true) String entityId,
			ToolContext toolContext) {
		UserInfo userInfo = extractUserInfo(toolContext);
		if (userInfo == null) {
			return new ToolResponse<>("No user context available");
		}
		try {
			IdAndVersion idAndVersion = IdAndVersion.parse(entityId);
			String synId = "syn" + idAndVersion.getId();
			Entity entity;
			if (idAndVersion.getVersion().isPresent()) {
				entity = entityService.getEntityForVersion(userInfo.getId(), synId, idAndVersion.getVersion().get());
			} else {
				entity = entityService.getEntity(userInfo.getId(), synId);
			}
			return new ToolResponse<>(entity);
		} catch (Exception e) {
			return new ToolResponse<>("Error getting details for entity '" + entityId + "': " + e.getMessage());
		}
	}

	@Tool(description = "Get all annotations for a Synapse entity, including annotations derived from a bound "
			+ "JSON schema. Use this to answer questions such as 'What annotations does syn123 have?'.",
			resultConverter = JSONEntityResultConverter.class)
	public ToolResponse<Annotations> getAnnotations(
			@ToolParam(description = "A Synapse entity ID such as 'syn123'", required = true) String entityId,
			ToolContext toolContext) {
		UserInfo userInfo = extractUserInfo(toolContext);
		if (userInfo == null) {
			return new ToolResponse<>("No user context available");
		}
		try {
			String synId = "syn" + IdAndVersion.parse(entityId).getId();
			Annotations annotations = entityService.getEntityAnnotations(userInfo.getId(), synId, true);
			return new ToolResponse<>(annotations);
		} catch (Exception e) {
			return new ToolResponse<>("Error getting annotations for entity '" + entityId + "': " + e.getMessage());
		}
	}

	@Tool(description = "Get the JSON schema binding for a Synapse entity, if one is bound. The binding "
			+ "identifies the schema $id that validates the entity and whether derived annotations are enabled.",
			resultConverter = JSONEntityResultConverter.class)
	public ToolResponse<JsonSchemaObjectBinding> getSchemaBinding(
			@ToolParam(description = "A Synapse entity ID such as 'syn123'", required = true) String entityId,
			ToolContext toolContext) {
		UserInfo userInfo = extractUserInfo(toolContext);
		if (userInfo == null) {
			return new ToolResponse<>("No user context available");
		}
		try {
			String synId = "syn" + IdAndVersion.parse(entityId).getId();
			JsonSchemaObjectBinding binding = entityService.getBoundSchema(userInfo.getId(), synId);
			return new ToolResponse<>(binding);
		} catch (Exception e) {
			return new ToolResponse<>("Error getting schema binding for entity '" + entityId + "': " + e.getMessage());
		}
	}

	@Tool(description = "List the child entities of a Synapse container (Project or Folder). Results are paged; "
			+ "if the response includes a nextPageToken, pass it back to retrieve the next page.",
			resultConverter = JSONEntityResultConverter.class)
	public ToolResponse<EntityChildrenResponse> getChildren(
			@ToolParam(description = "A Synapse entity ID of the parent container such as 'syn123'", required = true) String entityId,
			@ToolParam(description = "A page token from a previous response to get the next page of children", required = false) String nextPageToken,
			ToolContext toolContext) {
		UserInfo userInfo = extractUserInfo(toolContext);
		if (userInfo == null) {
			return new ToolResponse<>("No user context available");
		}
		try {
			String synId = "syn" + IdAndVersion.parse(entityId).getId();
			EntityChildrenResponse children = entityService.getChildren(userInfo.getId(), new EntityChildrenRequest()
					.setParentId(synId)
					.setIncludeTypes(List.of(EntityType.values()))
					.setIncludeTotalChildCount(true)
					.setSortBy(SortBy.MODIFIED_ON)
					.setSortDirection(Direction.DESC)
					.setNextPageToken(nextPageToken));
			return new ToolResponse<>(children);
		} catch (Exception e) {
			return new ToolResponse<>("Error getting children for entity '" + entityId + "': " + e.getMessage());
		}
	}

	@Tool(description = "Copy the contents of one or more Synapse FileEntities into the code interpreter session "
			+ "so they can be inspected or processed. The user must have download permission on each file. "
			+ "Returns a per-file report of which files were added and which could not be.")
	public String addFilesToSession(
			@ToolParam(description = "The files to add, each identified by a FileEntity ID (e.g. 'syn123' or "
					+ "'syn123.5') and the session path where it should appear (e.g. 'entity_metadata_specialist/data.csv')", required = true) List<FileToAdd> files,
			ToolContext toolContext) {
		UserInfo userInfo = extractUserInfo(toolContext);
		if (userInfo == null) {
			return "Error: No user context available";
		}
		String sessionId = extractSessionId(toolContext);
		if (sessionId == null) {
			return "Error: No code interpreter session ID available";
		}
		if (files == null || files.isEmpty()) {
			return "Error: No files were provided to add to the session";
		}
		try {
			// One outcome line per input file, kept in input order. Files that are not FileEntities
			// fail here; the remaining files are resolved to a READ-only entity and pushed as a batch,
			// which enforces DOWNLOAD authorization per-file rather than aborting the whole request.
			String[] outcomes = new String[files.size()];
			List<PushFileRequest> pushRequests = new ArrayList<>(files.size());
			List<Integer> pushToFileIndex = new ArrayList<>(files.size());

			for (int i = 0; i < files.size(); i++) {
				FileToAdd file = files.get(i);
				IdAndVersion idAndVersion = IdAndVersion.parse(file.entityId());
				String synId = "syn" + idAndVersion.getId();
				Entity entity;
				if (idAndVersion.getVersion().isPresent()) {
					entity = entityService.getEntityForVersion(userInfo.getId(), synId, idAndVersion.getVersion().get());
				} else {
					entity = entityService.getEntity(userInfo.getId(), synId);
				}

				if (!(entity instanceof FileEntity fileEntity)) {
					outcomes[i] = "Failed to add '" + file.entityId() + "': not a FileEntity";
					continue;
				}

				FileHandleAssociation association = new FileHandleAssociation()
						.setFileHandleId(fileEntity.getDataFileHandleId())
						.setAssociateObjectType(FileHandleAssociateType.FileEntity)
						.setAssociateObjectId(synId);
				pushRequests.add(new PushFileRequest(association, file.sessionPath()));
				pushToFileIndex.add(i);
			}

			if (!pushRequests.isEmpty()) {
				List<PushFileResult> results = codeInterpreterFileManager.pushFileHandlesToSession(userInfo,
						pushRequests, sessionId);
				for (int j = 0; j < pushToFileIndex.size(); j++) {
					int i = pushToFileIndex.get(j);
					FileToAdd file = files.get(i);
					PushFileResult result = results.get(j);
					if (result.isError()) {
						outcomes[i] = "Failed to add '" + file.entityId() + "': " + result.error();
					} else {
						outcomes[i] = "Added '" + file.entityId() + "' at '" + file.sessionPath() + "'";
					}
				}
			}

			StringBuilder report = new StringBuilder();
			for (String outcome : outcomes) {
				report.append(outcome).append("\n");
			}
			return report.toString().trim();
		} catch (Exception e) {
			return "Error adding files to session: " + e.getMessage();
		}
	}

	/**
	 * A single FileEntity to add to the code interpreter session.
	 *
	 * @param entityId    The FileEntity ID, optionally with a version (e.g. 'syn123' or 'syn123.5')
	 * @param sessionPath The path where the file should appear in the session filesystem
	 */
	public record FileToAdd(String entityId, String sessionPath) {}

	private UserInfo extractUserInfo(ToolContext toolContext) {
		return (UserInfo) toolContext.getContext().get("userInfo");
	}

	private String extractSessionId(ToolContext toolContext) {
		return (String) toolContext.getContext().get("sessionId");
	}

}
