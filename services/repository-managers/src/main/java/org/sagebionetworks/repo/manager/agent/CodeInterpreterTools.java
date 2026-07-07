package org.sagebionetworks.repo.manager.agent;

import java.time.Duration;
import java.util.Date;
import java.util.UUID;

import org.sagebionetworks.StackConfiguration;
import org.sagebionetworks.ids.IdGenerator;
import org.sagebionetworks.ids.IdType;
import org.sagebionetworks.repo.manager.file.FileHandleManager;
import org.sagebionetworks.repo.model.StorageLocationDAO;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dbo.file.FileHandleDao;
import org.sagebionetworks.repo.model.file.FileHandle;
import org.sagebionetworks.repo.model.file.S3FileHandle;
import org.sagebionetworks.upload.multipart.MultipartUtils;
import org.springaicommunity.agentcore.codeinterpreter.AgentCoreCodeInterpreterClient;
import org.springaicommunity.agentcore.codeinterpreter.CodeExecutionResult;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Service
public class CodeInterpreterTools {

	private final FileHandleManager fileHandleManager;
	private final S3Client s3Client;
	private final S3Presigner s3Presigner;
	private final AgentCoreCodeInterpreterClient codeInterpreterClient;
	private final FileHandleDao fileHandleDao;
	private final IdGenerator idGenerator;
	private final StorageLocationDAO storageLocationDAO;
	private final String stagingBucket;
	private final String synapseBucket;

	public CodeInterpreterTools(FileHandleManager fileHandleManager, S3Client s3Client, S3Presigner s3Presigner,
			AgentCoreCodeInterpreterClient codeInterpreterClient, StackConfiguration stackConfig,
			FileHandleDao fileHandleDao, IdGenerator idGenerator, StorageLocationDAO storageLocationDAO) {
		this.fileHandleManager = fileHandleManager;
		this.s3Client = s3Client;
		this.s3Presigner = s3Presigner;
		this.codeInterpreterClient = codeInterpreterClient;
		this.fileHandleDao = fileHandleDao;
		this.idGenerator = idGenerator;
		this.storageLocationDAO = storageLocationDAO;
		this.stagingBucket = stackConfig.getStack() + ".code-interpreter.staging.sagebase.org";
		this.synapseBucket = stackConfig.getS3Bucket();
	}

	@Tool(description = "Add a file to the current code interpreter session by its file handle ID. "
			+ "The file will be available at the returned path in the session's filesystem.")
	public String addFileToSession(String fileHandleId, ToolContext toolContext) {
		UserInfo userInfo = (UserInfo) toolContext.getContext().get("userInfo");
		if (userInfo == null) {
			return "Error: No user context available";
		}
		String sessionId = (String) toolContext.getContext().get("sessionId");
		if (sessionId == null) {
			return "Error: No code interpreter session ID available";
		}

		FileHandle fileHandle = fileHandleManager.getRawFileHandle(userInfo, fileHandleId);
		if (!(fileHandle instanceof S3FileHandle)) {
			return "Error: File handle '" + fileHandleId + "' is not an S3-backed file";
		}
		S3FileHandle s3Handle = (S3FileHandle) fileHandle;

		s3Client.copyObject(CopyObjectRequest.builder()
				.sourceBucket(s3Handle.getBucketName())
				.sourceKey(s3Handle.getKey())
				.destinationBucket(stagingBucket)
				.destinationKey(s3Handle.getKey())
				.build());

		String presignedUrl = s3Presigner.presignGetObject(GetObjectPresignRequest.builder()
				.getObjectRequest(r -> r.bucket(stagingBucket).key(s3Handle.getKey()))
				.signatureDuration(Duration.ofMinutes(15))
				.build()).url().toString();

		String fileName = s3Handle.getFileName();
		String downloadCode = String.join("\n",
				"import urllib.request",
				"url = \"\"\"" + presignedUrl + "\"\"\"",
				"urllib.request.urlretrieve(url, '" + fileName + "')",
				"print('File downloaded successfully')");

		CodeExecutionResult result = codeInterpreterClient.executeCode(sessionId, "python", downloadCode);
		if (result.isError()) {
			return "Error downloading file to session: " + result.textOutput();
		}
		return "File '" + fileName + "' is now available at './" + fileName + "'";
	}

	@Tool(description = "Export a file from the current code interpreter session and create a Synapse file handle. "
			+ "Returns the new file handle ID that can be used to attach the file to a Synapse entity.")
	public String getFileFromSession(String filePath, String contentType, ToolContext toolContext) {
		UserInfo userInfo = (UserInfo) toolContext.getContext().get("userInfo");
		if (userInfo == null) {
			return "Error: No user context available";
		}
		String sessionId = (String) toolContext.getContext().get("sessionId");
		if (sessionId == null) {
			return "Error: No code interpreter session ID available";
		}

		String userId = userInfo.getId().toString();
		String fileName = filePath.contains("/") ? filePath.substring(filePath.lastIndexOf('/') + 1) : filePath;
		String stagingKey = userId + "/" + UUID.randomUUID() + "/" + fileName;

		PresignedPutObjectRequest presignedPut = s3Presigner.presignPutObject(PutObjectPresignRequest.builder()
				.putObjectRequest(r -> r.bucket(stagingBucket).key(stagingKey).contentType(contentType))
				.signatureDuration(Duration.ofMinutes(15))
				.build());
		String putUrl = presignedPut.url().toString();

		String uploadCode = String.join("\n",
				"import urllib.request",
				"import os",
				"import hashlib",
				"",
				"file_path = '" + filePath + "'",
				"with open(file_path, 'rb') as f:",
				"    file_data = f.read()",
				"",
				"md5_hash = hashlib.md5(file_data).hexdigest()",
				"",
				"url = \"\"\"" + putUrl + "\"\"\"",
				"req = urllib.request.Request(url, data=file_data, method='PUT')",
				"req.add_header('Content-Type', '" + contentType + "')",
				"urllib.request.urlopen(req)",
				"",
				"print(f'{md5_hash}:{len(file_data)}')");

		CodeExecutionResult uploadResult = codeInterpreterClient.executeCode(sessionId, "python", uploadCode);
		if (uploadResult.isError()) {
			return "Error uploading file from session: " + uploadResult.textOutput();
		}

		String output = uploadResult.textOutput().trim();
		String[] parts = output.split(":");
		if (parts.length != 2) {
			return "Error: Unexpected output from upload script: " + output;
		}
		String md5 = parts[0];
		long contentSize = Long.parseLong(parts[1]);

		String synapseKey = MultipartUtils.createNewKey(userId, fileName,
				storageLocationDAO.get(StorageLocationDAO.DEFAULT_STORAGE_LOCATION_ID));

		s3Client.copyObject(CopyObjectRequest.builder()
				.sourceBucket(stagingBucket)
				.sourceKey(stagingKey)
				.destinationBucket(synapseBucket)
				.destinationKey(synapseKey)
				.build());

		S3FileHandle handle = new S3FileHandle();
		handle.setStorageLocationId(StorageLocationDAO.DEFAULT_STORAGE_LOCATION_ID);
		handle.setBucketName(synapseBucket);
		handle.setKey(synapseKey);
		handle.setContentMd5(md5);
		handle.setContentType(contentType);
		handle.setContentSize(contentSize);
		handle.setFileName(fileName);
		handle.setCreatedBy(userId);
		handle.setCreatedOn(new Date());
		handle.setId(idGenerator.generateNewId(IdType.FILE_IDS).toString());
		handle.setEtag(UUID.randomUUID().toString());

		S3FileHandle created = (S3FileHandle) fileHandleDao.createFile(handle);
		return created.getId();
	}
}
