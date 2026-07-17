package org.sagebionetworks.repo.manager.agent;

import java.io.File;
import java.io.StringWriter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader;
import org.sagebionetworks.StackConfiguration;
import org.sagebionetworks.repo.manager.file.FileHandleManager;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.file.BatchFileRequest;
import org.sagebionetworks.repo.model.file.BatchFileResult;
import org.sagebionetworks.repo.model.file.FileHandleAssociation;
import org.sagebionetworks.repo.model.file.FileResult;
import org.sagebionetworks.repo.model.file.S3FileHandle;
import org.sagebionetworks.util.ValidateArgument;
import org.springaicommunity.agentcore.codeinterpreter.AgentCoreCodeInterpreterClient;
import org.springaicommunity.agentcore.codeinterpreter.CodeExecutionResult;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Service
public class CodeInterpreterFileManager {

	static final String DOWNLOAD_TEMPLATE = "code-templates/code-interpreter-download.py.vtp";
	static final String UPLOAD_TEMPLATE = "code-templates/code-interpreter-upload.py.vtp";

	private final S3Client s3Client;
	private final S3Presigner s3Presigner;
	private final AgentCoreCodeInterpreterClient codeInterpreterClient;
	private final FileHandleManager fileHandleManager;
	private final String stagingBucket;
	private final VelocityEngine velocityEngine;

	public CodeInterpreterFileManager(S3Client s3Client, S3Presigner s3Presigner,
			AgentCoreCodeInterpreterClient codeInterpreterClient, FileHandleManager fileHandleManager,
			StackConfiguration stackConfig) {
		this.s3Client = s3Client;
		this.s3Presigner = s3Presigner;
		this.codeInterpreterClient = codeInterpreterClient;
		this.fileHandleManager = fileHandleManager;
		this.stagingBucket = stackConfig.getStack() + ".code-interpreter.staging.sagebase.org";
		this.velocityEngine = new VelocityEngine();
		this.velocityEngine.setProperty(RuntimeConstants.RESOURCE_LOADER, "classpath");
		this.velocityEngine.setProperty("classpath.resource.loader.class", ClasspathResourceLoader.class.getName());
		this.velocityEngine.setProperty("runtime.references.strict", true);
	}

	/**
	 * Push a file from the Synapse S3 bucket to the code interpreter session.
	 * Copies the object to the staging bucket, generates a presigned GET URL,
	 * and executes a Python download script in the session.
	 *
	 * @param sessionId    The code interpreter session ID
	 * @param sourceBucket The S3 bucket containing the source file
	 * @param sourceKey    The S3 key of the source file
	 * @param sessionPath  The filename/path where the file will appear in the session
	 * @return The code execution result from the download script
	 */
	public CodeExecutionResult pushS3FileToSession(String sessionId, String sourceBucket, String sourceKey,
			String sessionPath) {
		s3Client.copyObject(CopyObjectRequest.builder()
				.sourceBucket(sourceBucket)
				.sourceKey(sourceKey)
				.destinationBucket(stagingBucket)
				.destinationKey(sourceKey)
				.build());

		String presignedUrl = s3Presigner.presignGetObject(GetObjectPresignRequest.builder()
				.getObjectRequest(r -> r.bucket(stagingBucket).key(sourceKey))
				.signatureDuration(Duration.ofMinutes(15))
				.build()).url().toString();

		VelocityContext context = new VelocityContext();
		context.put("presignedUrl", presignedUrl);
		context.put("fileName", sessionPath);
		String downloadCode = renderTemplate(DOWNLOAD_TEMPLATE, context);

		return codeInterpreterClient.executeCode(sessionId, "python", downloadCode);
	}

	/**
	 * Push a batch of Synapse files, identified by their file handle associations, into a code
	 * interpreter session. Download authorization is enforced for each file via
	 * {@link FileHandleManager#getFileHandleAndUrlBatch(UserInfo, BatchFileRequest)}: a file the
	 * user cannot download is reported as a failure rather than staged. Batching the authorization
	 * and file handle resolution into a single call keeps this efficient for multi-file requests.
	 *
	 * @param user     The user on whose behalf the files are pushed; used for download authorization
	 * @param requests The files to push, each pairing a file handle association with the session path
	 *                 where it should appear
	 * @param sessionId The code interpreter session ID
	 * @return One {@link PushFileResult} per request, preserving input order, describing whether each
	 *         file was staged into the session or why it could not be
	 */
	public List<PushFileResult> pushFileHandlesToSession(UserInfo user, List<PushFileRequest> requests,
			String sessionId) {
		ValidateArgument.required(user, "user");
		ValidateArgument.requiredNotEmpty(requests, "requests");
		ValidateArgument.required(sessionId, "sessionId");

		List<FileHandleAssociation> associations = new ArrayList<>(requests.size());
		for (PushFileRequest request : requests) {
			associations.add(request.association());
		}

		BatchFileResult batchResult = fileHandleManager.getFileHandleAndUrlBatch(user, new BatchFileRequest()
				.setRequestedFiles(associations)
				.setIncludeFileHandles(true)
				.setIncludePreSignedURLs(false)
				.setIncludePreviewPreSignedURLs(false));

		// getFileHandleAndUrlBatch returns one FileResult per requested file, in request order, so
		// each result lines up positionally with its originating request.
		List<PushFileResult> results = new ArrayList<>(requests.size());
		for (int i = 0; i < requests.size(); i++) {
			PushFileRequest request = requests.get(i);
			FileResult fileResult = batchResult.getRequestedFiles().get(i);

			if (fileResult.getFailureCode() != null) {
				results.add(PushFileResult.failure(request, fileResult.getFailureCode().name()));
			} else if (!(fileResult.getFileHandle() instanceof S3FileHandle s3Handle)) {
				results.add(PushFileResult.failure(request, "File handle '" + request.association().getFileHandleId()
						+ "' is not an S3-backed file"));
			} else {
				CodeExecutionResult execution = pushS3FileToSession(sessionId, s3Handle.getBucketName(),
						s3Handle.getKey(), request.sessionPath());
				results.add(PushFileResult.staged(request, execution));
			}
		}
		return results;
	}

	/**
	 * A request to push a single Synapse file into a code interpreter session.
	 *
	 * @param association The file handle association identifying the file and its authorization context
	 * @param sessionPath The path where the file should appear in the session filesystem
	 */
	public record PushFileRequest(FileHandleAssociation association, String sessionPath) {}

	/**
	 * The outcome of attempting to push a single file into a session.
	 *
	 * @param request   The originating request
	 * @param execution The code interpreter download result when the file was staged; null on failure
	 * @param error     The reason the file could not be staged; null on success
	 */
	public record PushFileResult(PushFileRequest request, CodeExecutionResult execution, String error) {
		static PushFileResult staged(PushFileRequest request, CodeExecutionResult execution) {
			return new PushFileResult(request, execution, execution.isError() ? execution.textOutput() : null);
		}

		static PushFileResult failure(PushFileRequest request, String error) {
			return new PushFileResult(request, null, error);
		}

		public boolean isError() {
			return error != null;
		}
	}

	/**
	 * Push a local file to the code interpreter session via the staging bucket.
	 * Uploads the file to the staging bucket, generates a presigned GET URL,
	 * and executes a Python download script in the session.
	 *
	 * @param sessionId   The code interpreter session ID
	 * @param localFile   The local file to push
	 * @param contentType The content type of the file (e.g., "text/csv", "application/json")
	 * @param sessionPath The filename/path where the file will appear in the session
	 * @return The code execution result from the download script
	 */
	public CodeExecutionResult pushLocalFileToSession(String sessionId, File localFile, String contentType, String sessionPath) {
		String stagingKey = UUID.randomUUID() + "/" + localFile.getName();

		s3Client.putObject(
				PutObjectRequest.builder()
						.bucket(stagingBucket)
						.key(stagingKey)
						.contentType(contentType)
						.build(),
				localFile.toPath());

		String presignedUrl = s3Presigner.presignGetObject(GetObjectPresignRequest.builder()
				.getObjectRequest(r -> r.bucket(stagingBucket).key(stagingKey))
				.signatureDuration(Duration.ofMinutes(15))
				.build()).url().toString();

		VelocityContext context = new VelocityContext();
		context.put("presignedUrl", presignedUrl);
		context.put("fileName", sessionPath);
		String downloadCode = renderTemplate(DOWNLOAD_TEMPLATE, context);

		return codeInterpreterClient.executeCode(sessionId, "python", downloadCode);
	}

	/**
	 * Pull a file from the code interpreter session to the staging bucket.
	 * Generates a presigned PUT URL, executes a Python upload script in the session,
	 * and returns the result metadata (md5:contentSize format in textOutput).
	 *
	 * @param sessionId   The code interpreter session ID
	 * @param sessionPath The path of the file in the session
	 * @param contentType The content type for the uploaded file
	 * @param userId      The user ID (used to namespace the staging key)
	 * @return A {@link PullResult} containing the staging key, md5, and content size
	 */
	public PullResult pullFileFromSession(String sessionId, String sessionPath, String contentType, String userId) {
		String fileName = sessionPath.contains("/") ? sessionPath.substring(sessionPath.lastIndexOf('/') + 1) : sessionPath;
		String stagingKey = userId + "/" + UUID.randomUUID() + "/" + fileName;

		PresignedPutObjectRequest presignedPut = s3Presigner.presignPutObject(PutObjectPresignRequest.builder()
				.putObjectRequest(r -> r.bucket(stagingBucket).key(stagingKey).contentType(contentType))
				.signatureDuration(Duration.ofMinutes(15))
				.build());
		String putUrl = presignedPut.url().toString();

		VelocityContext context = new VelocityContext();
		context.put("filePath", sessionPath);
		context.put("presignedUrl", putUrl);
		context.put("contentType", contentType);
		String uploadCode = renderTemplate(UPLOAD_TEMPLATE, context);

		CodeExecutionResult uploadResult = codeInterpreterClient.executeCode(sessionId, "python", uploadCode);
		if (uploadResult.isError()) {
			throw new RuntimeException("Error uploading file from session: " + uploadResult.textOutput());
		}

		String output = uploadResult.textOutput().trim();
		String[] parts = output.split(":");
		if (parts.length != 2) {
			throw new RuntimeException("Unexpected output from upload script: " + output);
		}

		return new PullResult(stagingBucket, stagingKey, parts[0], Long.parseLong(parts[1]));
	}

	public String getStagingBucket() {
		return stagingBucket;
	}

	private String renderTemplate(String templateName, VelocityContext context) {
		Template template = velocityEngine.getTemplate(templateName);
		StringWriter writer = new StringWriter();
		template.merge(context, writer);
		return writer.toString();
	}

	public record PullResult(String bucket, String key, String md5, long contentSize) {}
}
