package org.sagebionetworks.repo.manager.file;

import static org.sagebionetworks.downloadtools.FileUtils.DEFAULT_FILE_CHARSET;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import org.apache.commons.fileupload.FileItemIterator;
import org.apache.commons.fileupload.FileItemStream;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.util.Streams;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpHeaders;
import org.apache.http.entity.ContentType;
import org.sagebionetworks.StackConfiguration;
import org.sagebionetworks.downloadtools.FileUtils;
import org.sagebionetworks.repo.manager.AuthorizationManager;
import org.sagebionetworks.repo.manager.AuthorizationManagerUtil;
import org.sagebionetworks.repo.manager.NodeManager;
import org.sagebionetworks.repo.manager.ProjectSettingsManager;
import org.sagebionetworks.repo.manager.file.transfer.FileTransferStrategy;
import org.sagebionetworks.repo.manager.file.transfer.TransferRequest;
import org.sagebionetworks.repo.manager.file.transfer.TransferUtils;
import org.sagebionetworks.repo.model.DatastoreException;
import org.sagebionetworks.repo.model.EntityHeader;
import org.sagebionetworks.repo.model.StorageLocationDAO;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dao.FileHandleDao;
import org.sagebionetworks.repo.model.dao.UploadDaemonStatusDao;
import org.sagebionetworks.repo.model.dbo.dao.DBOStorageLocationDAOImpl;
import org.sagebionetworks.repo.model.file.ChunkRequest;
import org.sagebionetworks.repo.model.file.ChunkResult;
import org.sagebionetworks.repo.model.file.ChunkedFileToken;
import org.sagebionetworks.repo.model.file.CompleteAllChunksRequest;
import org.sagebionetworks.repo.model.file.CompleteChunkedFileRequest;
import org.sagebionetworks.repo.model.file.CreateChunkedFileTokenRequest;
import org.sagebionetworks.repo.model.file.ExternalFileHandle;
import org.sagebionetworks.repo.model.file.ExternalS3UploadDestination;
import org.sagebionetworks.repo.model.file.ExternalUploadDestination;
import org.sagebionetworks.repo.model.file.FileHandle;
import org.sagebionetworks.repo.model.file.FileHandleResults;
import org.sagebionetworks.repo.model.file.HasPreviewId;
import org.sagebionetworks.repo.model.file.S3FileHandle;
import org.sagebionetworks.repo.model.file.S3FileHandleInterface;
import org.sagebionetworks.repo.model.file.S3UploadDestination;
import org.sagebionetworks.repo.model.file.State;
import org.sagebionetworks.repo.model.file.UploadDaemonStatus;
import org.sagebionetworks.repo.model.file.UploadDestination;
import org.sagebionetworks.repo.model.file.UploadDestinationLocation;
import org.sagebionetworks.repo.model.file.UploadType;
import org.sagebionetworks.repo.model.project.ExternalS3StorageLocationSetting;
import org.sagebionetworks.repo.model.project.ExternalStorageLocationSetting;
import org.sagebionetworks.repo.model.project.ProjectSettingsType;
import org.sagebionetworks.repo.model.project.S3StorageLocationSetting;
import org.sagebionetworks.repo.model.project.StorageLocationSetting;
import org.sagebionetworks.repo.model.project.UploadDestinationListSetting;
import org.sagebionetworks.repo.model.util.ContentTypeUtils;
import org.sagebionetworks.repo.transactions.WriteTransaction;
import org.sagebionetworks.repo.web.NotFoundException;
import org.sagebionetworks.repo.web.ServiceUnavailableException;
import org.sagebionetworks.util.ValidateArgument;
import org.sagebionetworks.utils.ContentTypeUtil;
import org.sagebionetworks.utils.MD5ChecksumHelper;
import org.springframework.beans.factory.annotation.Autowired;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.HttpMethod;
import com.amazonaws.event.ProgressListener;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.BucketCrossOriginConfiguration;
import com.amazonaws.services.s3.model.CORSRule;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import com.amazonaws.services.s3.model.CORSRule.AllowedMethods;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.ResponseHeaderOverrides;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.amazonaws.util.BinaryUtils;
import com.google.common.collect.Lists;

/**
 * Basic implementation of the file upload manager.
 * 
 * @author John
 * 
 */
public class FileHandleManagerImpl implements FileHandleManager {

	public static final long PRESIGNED_URL_EXPIRE_TIME_MS = 30 * 1000; // 30
																		// secs

	/**
	 * Used as the file contents for old locationables and attachments that were never
	 * successfully uploaded by the original user.  See PLFM-3266.
	 */
	static private String NEVER_UPLOADED_CONTENTS = "Placeholder for a file that has not been uploaded.";
	/**
	 * Used as the file name for old locationables and attachments that were never
	 * successfully uploaded by the original user.  See PLFM-3266.
	 */
	private static final String PLACEHOLDER_SUFFIX = "_placeholder.txt";

	static private Log log = LogFactory.getLog(FileHandleManagerImpl.class);

	private static String FILE_TOKEN_TEMPLATE = "%1$s/%2$s/%3$s"; // userid/UUID/filename

	public static final String NOT_SET = "NOT_SET";
	
	private static final String DEFAULT_COMPRESSED_FILE_NAME = "compressed.txt.gz";
	
	private static final String GZIP_CONTENT_ENCODING = "gzip";

	@Autowired
	FileHandleDao fileHandleDao;

	@Autowired
	AuthorizationManager authorizationManager;

	@Autowired
	AmazonS3Client s3Client;

	@Autowired
	UploadDaemonStatusDao uploadDaemonStatusDao;

	@Autowired
	ExecutorService uploadFileDaemonThreadPoolPrimary;

	@Autowired
	ExecutorService uploadFileDaemonThreadPoolSecondary;

	@Autowired
	MultipartManager multipartManager;

	@Autowired
	ProjectSettingsManager projectSettingsManager;
	
	@Autowired
	StorageLocationDAO storageLocationDAO;

	@Autowired
	NodeManager nodeManager;

	/**
	 * This is the first strategy we try to use.
	 */
	FileTransferStrategy primaryStrategy;
	/**
	 * When the primaryStrategy fails, we try fall-back strategy
	 * 
	 */
	FileTransferStrategy fallbackStrategy;

	/**
	 * This is the maximum amount of time the upload workers are allowed to take
	 * before timing out.
	 */
	long multipartUploadDaemonTimeoutMS;

	/**
	 * This is the maximum amount of time the upload workers are allowed to take
	 * before timing out. Injected via Spring
	 * 
	 * @param multipartUploadDaemonTimeoutMS
	 */
	public void setMultipartUploadDaemonTimeoutMS(
			long multipartUploadDaemonTimeoutMS) {
		this.multipartUploadDaemonTimeoutMS = multipartUploadDaemonTimeoutMS;
	}

	/**
	 * Used by spring
	 */
	public FileHandleManagerImpl() {
		super();
	}

	/**
	 * The IoC constructor.
	 * 
	 * @param fileMetadataDao
	 * @param primaryStrategy
	 * @param fallbackStrategy
	 * @param authorizationManager
	 * @param s3Client
	 */
	public FileHandleManagerImpl(FileHandleDao fileMetadataDao,
			FileTransferStrategy primaryStrategy,
			FileTransferStrategy fallbackStrategy,
			AuthorizationManager authorizationManager, AmazonS3Client s3Client) {
		super();
		this.fileHandleDao = fileMetadataDao;
		this.primaryStrategy = primaryStrategy;
		this.fallbackStrategy = fallbackStrategy;
		this.authorizationManager = authorizationManager;
		this.s3Client = s3Client;
	}

	/**
	 * Inject the primary strategy.
	 * 
	 * @param primaryStrategy
	 */
	public void setPrimaryStrategy(FileTransferStrategy primaryStrategy) {
		this.primaryStrategy = primaryStrategy;
	}

	/**
	 * Inject the fall-back strategy.
	 * 
	 * @param fallbackStrategy
	 */
	public void setFallbackStrategy(FileTransferStrategy fallbackStrategy) {
		this.fallbackStrategy = fallbackStrategy;
	}

	@WriteTransaction
	@Override
	public FileUploadResults uploadfiles(UserInfo userInfo,
			Set<String> expectedParams, FileItemIterator itemIterator)
			throws FileUploadException, IOException,
			ServiceUnavailableException {
		if (userInfo == null)
			throw new IllegalArgumentException("UserInfo cannot be null");
		if (expectedParams == null)
			throw new IllegalArgumentException("UserInfo cannot be null");
		if (itemIterator == null)
			throw new IllegalArgumentException(
					"FileItemIterator cannot be null");
		if (primaryStrategy == null)
			throw new IllegalStateException(
					"The primaryStrategy has not been set.");
		if (fallbackStrategy == null)
			throw new IllegalStateException(
					"The fallbackStrategy has not been set.");
		FileUploadResults results = new FileUploadResults();
		String userId = getUserId(userInfo);
		// Upload all of the files
		// Before we try to read any files make sure we have all of the expected
		// parameters.
		Set<String> expectedCopy = new HashSet<String>(expectedParams);
		while (itemIterator.hasNext()) {
			FileItemStream fis = itemIterator.next();
			if (fis.isFormField()) {
				// This is a parameter
				// By removing it from the set we indicate that it was found.
				expectedCopy.remove(fis.getFieldName());
				// Map parameter in the results
				results.getParameters().put(fis.getFieldName(),
						Streams.asString(fis.openStream()));
			} else {
				// This is a file
				if (!expectedCopy.isEmpty()) {
					// We are missing some required parameters
					throw new IllegalArgumentException(
							"Missing one or more of the expected form fields: "
									+ expectedCopy);
				}
				S3FileHandle s3Meta = uploadFile(userId, fis);
				// If here then we succeeded
				results.getFiles().add(s3Meta);
			}
		}
		if (log.isDebugEnabled()) {
			log.debug(results);
		}
		return results;
	}

	/**
	 * Get the User's ID
	 * 
	 * @param userInfo
	 * @return
	 */
	public String getUserId(UserInfo userInfo) {
		return userInfo.getId().toString();
	}

	/**
	 * @param userId
	 * @param fis
	 * @return
	 * @throws IOException
	 * @throws ServiceUnavailableException
	 */
	@WriteTransaction
	@Override
	@Deprecated
	public S3FileHandle uploadFile(String userId, FileItemStream fis)
			throws IOException, ServiceUnavailableException {
		// Create a token for this file
		TransferRequest request = createRequest(fis.getContentType(), userId,
				fis.getName(), fis.openStream());
		S3FileHandle s3Meta = null;
		try {
			// Try the primary
			s3Meta = primaryStrategy.transferToS3(request);
		} catch (ServiceUnavailableException e) {
			log.info("The primary file transfer strategy failed, attempting to use the fall-back strategy.");
			// The primary strategy failed so try the fall-back.
			s3Meta = fallbackStrategy.transferToS3(request);
		}
		// set this user as the creator of the file
		s3Meta.setCreatedBy(userId);
		// Save the file metadata to the DB.
		s3Meta = fileHandleDao.createFile(s3Meta);
		return s3Meta;
	}

	/**
	 * Build up the S3FileMetadata.
	 * 
	 * @param contentType
	 * @param userId
	 * @param fileName
	 * @return
	 */
	public static TransferRequest createRequest(String contentType,
			String userId, String fileName, InputStream inputStream) {
		// Create a token for this file
		TransferRequest request = new TransferRequest();
		request.setContentType(ContentTypeUtils.getContentType(contentType,
				fileName));
		request.setS3bucketName(StackConfiguration.getS3Bucket());
		request.setS3key(createNewKey(userId, fileName));
		request.setFileName(fileName);
		request.setInputStream(inputStream);
		return request;
	}

	/**
	 * Create a new key
	 * 
	 * @param userId
	 * @param fileName
	 * @return
	 */
	private static String createNewKey(String userId, String fileName) {
		return String.format(FILE_TOKEN_TEMPLATE, userId, UUID.randomUUID()
				.toString(), fileName);
	}

	@Override
	public FileHandle getRawFileHandle(UserInfo userInfo, String handleId)
			throws DatastoreException, NotFoundException {
		if (userInfo == null)
			throw new IllegalArgumentException("UserInfo cannot be null");
		if (handleId == null)
			throw new IllegalArgumentException("FileHandleId cannot be null");
		// Get the file handle
		FileHandle handle = fileHandleDao.get(handleId);
		// Only the user that created this handle is authorized to get it.
		AuthorizationManagerUtil
				.checkAuthorizationAndThrowException(authorizationManager
						.canAccessRawFileHandleByCreator(userInfo, handleId,
								handle.getCreatedBy()));
		return handle;
	}

	@WriteTransaction
	@Override
	public void deleteFileHandle(UserInfo userInfo, String handleId)
			throws DatastoreException {
		if (userInfo == null)
			throw new IllegalArgumentException("UserInfo cannot be null");
		if (handleId == null)
			throw new IllegalArgumentException("FileHandleId cannot be null");
		// Get the file handle
		try {
			FileHandle handle = fileHandleDao.get(handleId);
			// Is the user authorized?
			AuthorizationManagerUtil
					.checkAuthorizationAndThrowException(authorizationManager
							.canAccessRawFileHandleByCreator(userInfo,
									handleId, handle.getCreatedBy()));
			// If this file has a preview then we want to delete the preview as
			// well.
			if (handle instanceof HasPreviewId) {
				HasPreviewId hasPreview = (HasPreviewId) handle;
				if (hasPreview.getPreviewId() != null
						&& !handle.getId().equals(hasPreview.getPreviewId())) {
					// Delete the preview.
					deleteFileHandle(userInfo, hasPreview.getPreviewId());
				}
			}
			// Is this an S3 file?
			if (handle instanceof S3FileHandleInterface) {
				S3FileHandleInterface s3Handle = (S3FileHandleInterface) handle;
				// at this point, we need to note that multiple S3FileHandles can point to the same bucket/key. We need
				// to check if this is the last S3FileHandle to point to this S3 object
				if (fileHandleDao.getS3objectReferenceCount(s3Handle.getBucketName(), s3Handle.getKey()) <= 1) {
					// Delete the file from S3
					s3Client.deleteObject(s3Handle.getBucketName(), s3Handle.getKey());
				}
			}
			// Delete the handle from the DB
			fileHandleDao.delete(handleId);
		} catch (NotFoundException e) {
			// there is nothing to do if the handle does not exist.
			return;
		}

	}

	@Override
	public String getRedirectURLForFileHandle(String handleId)
			throws DatastoreException, NotFoundException {
		// First lookup the file handle
		FileHandle handle = fileHandleDao.get(handleId);
		return getURLForFileHandle(handle);
	}

	/**
	 * @param handle
	 * @return
	 */
	public String getURLForFileHandle(FileHandle handle) {
		if (handle instanceof ExternalFileHandle) {
			ExternalFileHandle efh = (ExternalFileHandle) handle;
			return efh.getExternalURL();
		} else if (handle instanceof S3FileHandleInterface) {
			S3FileHandleInterface s3File = (S3FileHandleInterface) handle;
			// Create a pre-signed url
			GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(s3File.getBucketName(), s3File.getKey(), HttpMethod.GET);
			request.setExpiration(new Date(System.currentTimeMillis() + PRESIGNED_URL_EXPIRE_TIME_MS));

			ResponseHeaderOverrides responseHeaderOverrides = new ResponseHeaderOverrides();

			String contentType = handle.getContentType();
			if (StringUtils.isNotEmpty(contentType) && !NOT_SET.equals(contentType)) {
				responseHeaderOverrides.setContentType(contentType);
			}
			String fileName = handle.getFileName();
			if (StringUtils.isNotEmpty(fileName) && !NOT_SET.equals(fileName)) {
				responseHeaderOverrides.setContentDisposition("attachment; filename=" + fileName);
			}

			request.setResponseHeaders(responseHeaderOverrides);
			return s3Client.generatePresignedUrl(request).toExternalForm();
		} else {
			throw new IllegalArgumentException("Unknown FileHandle class: "
					+ handle.getClass().getName());
		}
	}

	@Override
	public String getPreviewFileHandleId(String handleId)
			throws DatastoreException, NotFoundException {
		return fileHandleDao.getPreviewFileHandleId(handleId);
	}

	@Override
	public void clearPreview(UserInfo userInfo, String handleId)
			throws DatastoreException, NotFoundException {
		if (userInfo == null)
			throw new IllegalArgumentException("UserInfo cannot be null");
		if (handleId == null)
			throw new IllegalArgumentException("FileHandleId cannot be null");

		// Get the file handle
		FileHandle handle = fileHandleDao.get(handleId);
		// Is the user authorized?
		if (!authorizationManager.canAccessRawFileHandleByCreator(userInfo,
				handleId, handle.getCreatedBy()).getAuthorized()) {
			throw new UnauthorizedException(
					"Only the creator of a FileHandle can clear the preview");
		}

		// clear the preview id
		fileHandleDao.setPreviewId(handleId, null);
	}

	@Override
	public FileHandleResults getAllFileHandles(Iterable<String> idList, boolean includePreviews) throws DatastoreException, NotFoundException {
		return fileHandleDao.getAllFileHandles(idList, includePreviews);
	}

	@Override
	public Map<String, FileHandle> getAllFileHandlesBatch(List<String> idsList)
			throws DatastoreException, NotFoundException {
		return fileHandleDao.getAllFileHandlesBatch(idsList);
	}

	@WriteTransaction
	@Override
	public ExternalFileHandle createExternalFileHandle(UserInfo userInfo,
			ExternalFileHandle fileHandle) {
		if (userInfo == null)
			throw new IllegalArgumentException("UserInfo cannot be null");
		if (fileHandle == null)
			throw new IllegalArgumentException("FileHandle cannot be null");
		if (fileHandle.getExternalURL() == null)
			throw new IllegalArgumentException("ExternalURL cannot be null");
		if (fileHandle.getFileName() == null) {
			fileHandle.setFileName(NOT_SET);
		}
		if (fileHandle.getContentType() == null) {
			fileHandle.setContentType(NOT_SET);
		}
		// The URL must be a URL
		ValidateArgument.validUrl(fileHandle.getExternalURL());
		// set this user as the creator of the file
		fileHandle.setCreatedBy(getUserId(userInfo));
		// Save the file metadata to the DB.
		return fileHandleDao.createFile(fileHandle);
	}

	/**
	 * Called by Spring when after the bean is created..
	 */
	public void initialize() {
		// We need to ensure that Cross-Origin Resource Sharing (CORS) is
		// enabled on the bucket
		String bucketName = StackConfiguration.getS3Bucket();
		BucketCrossOriginConfiguration bcoc = s3Client
				.getBucketCrossOriginConfiguration(bucketName);
		if (bcoc == null || bcoc.getRules() == null
				|| bcoc.getRules().size() < 1) {
			// Set the CORS
			resetBuckCORS(bucketName);
		} else {
			// There can only be on rule on the bucket
			if (bcoc.getRules().size() > 1) {
				// rest the
				resetBuckCORS(bucketName);
			} else {
				// Check the rule
				CORSRule currentRule = bcoc.getRules().get(0);
				if (!FileHandleManager.AUTO_GENERATED_ALLOW_ALL_CORS_RULE_ID
						.equals(currentRule.getId())) {
					// rest the rule
					resetBuckCORS(bucketName);
				}
			}
		}
	}

	/**
	 * Reset the bucket's Cross-Origin Resource Sharing (CORS).
	 * 
	 * @param bucketName
	 */
	private void resetBuckCORS(String bucketName) {
		log.debug("Setting the buck Cross-Origin Resource Sharing (CORS) on bucket: "
				+ bucketName + " for the first time...");
		// We need to add the rules
		BucketCrossOriginConfiguration bcoc = new BucketCrossOriginConfiguration();
		CORSRule allowAll = new CORSRule();
		allowAll.setId(FileHandleManager.AUTO_GENERATED_ALLOW_ALL_CORS_RULE_ID);
		allowAll.setAllowedOrigins("*");
		allowAll.setAllowedMethods(AllowedMethods.GET, AllowedMethods.PUT,
				AllowedMethods.POST, AllowedMethods.HEAD);
		allowAll.setMaxAgeSeconds(300);
		allowAll.setAllowedHeaders("*");
		bcoc.withRules(allowAll);
		s3Client.setBucketCrossOriginConfiguration(
				StackConfiguration.getS3Bucket(), bcoc);
		log.info("Set CORSRule on bucket: " + bucketName + " to be: "
				+ allowAll);
	}

	@Override
	public BucketCrossOriginConfiguration getBucketCrossOriginConfiguration() {
		String bucketName = StackConfiguration.getS3Bucket();
		return s3Client.getBucketCrossOriginConfiguration(bucketName);
	}

	@Override
	public ChunkedFileToken createChunkedFileUploadToken(UserInfo userInfo, CreateChunkedFileTokenRequest ccftr) throws DatastoreException,
			NotFoundException {
		if (userInfo == null)
			throw new IllegalArgumentException("UserInfo cannot be null");
		String userId = getUserId(userInfo);
		return this.multipartManager.createChunkedFileUploadToken(ccftr, ccftr.getStorageLocationId(), userId);
	}

	@Override
	public URL createChunkedFileUploadPartURL(UserInfo userInfo, ChunkRequest cpr) throws DatastoreException, NotFoundException {
		if (cpr == null)
			throw new IllegalArgumentException(
					"ChunkedPartRequest cannot be null");
		if (cpr.getChunkedFileToken() == null)
			throw new IllegalArgumentException(
					"ChunkedPartRequest.chunkedFileToken cannot be null");
		if (cpr.getChunkNumber() == null)
			throw new IllegalArgumentException(
					"ChunkedPartRequest.chunkNumber cannot be null");
		ChunkedFileToken token = cpr.getChunkedFileToken();
		// first validate the token
		validateChunkedFileToken(userInfo, token);
		return multipartManager.createChunkedFileUploadPartURL(cpr, token.getStorageLocationId());
	}

	@Override
	public ChunkResult addChunkToFile(UserInfo userInfo, ChunkRequest cpr) throws DatastoreException, NotFoundException {
		if (cpr == null)
			throw new IllegalArgumentException(
					"ChunkedPartRequest cannot be null");
		if (cpr.getChunkedFileToken() == null)
			throw new IllegalArgumentException(
					"ChunkedPartRequest.chunkedFileToken cannot be null");
		if (cpr.getChunkNumber() == null)
			throw new IllegalArgumentException(
					"ChunkedPartRequest.chunkNumber cannot be null");
		ChunkedFileToken token = cpr.getChunkedFileToken();
		int partNumber = cpr.getChunkNumber().intValue();
		// first validate the token
		validateChunkedFileToken(userInfo, token);

		// The part number cannot be less than one
		if (partNumber < 1)
			throw new IllegalArgumentException(
					"partNumber cannot be less than one");
		ChunkResult result = this.multipartManager.copyPart(token, partNumber, token.getStorageLocationId());
		// Now delete the original file since we now have a copy
		String partkey = this.multipartManager.getChunkPartKey(token, partNumber);
		String bucket = this.multipartManager.getBucket(token.getStorageLocationId());
		s3Client.deleteObject(bucket, partkey);
		return result;
	}

	@WriteTransaction
	@Override
	public S3FileHandle completeChunkFileUpload(UserInfo userInfo, CompleteChunkedFileRequest ccfr) throws DatastoreException,
			NotFoundException {
		if (ccfr == null)
			throw new IllegalArgumentException(
					"CompleteChunkedFileRequest cannot be null");
		ChunkedFileToken token = ccfr.getChunkedFileToken();
		// first validate the token
		validateChunkedFileToken(userInfo, token);
		String userId = getUserId(userInfo);
		// Complete the multi-part
		return this.multipartManager.completeChunkFileUpload(ccfr, token.getStorageLocationId(), userId);
	}

	/**
	 * Validate that the user owns the token
	 */
	void validateChunkedFileToken(UserInfo userInfo, ChunkedFileToken token) {
		if (userInfo == null)
			throw new IllegalArgumentException("UserInfo cannot be null");
		if (token == null)
			throw new IllegalArgumentException(
					"ChunkedFileToken cannot be null");
		if (token.getKey() == null)
			throw new IllegalArgumentException(
					"ChunkedFileToken.key cannot be null");
		if (token.getUploadId() == null)
			throw new IllegalArgumentException(
					"ChunkedFileToken.uploadId cannot be null");
		if (token.getFileName() == null)
			throw new IllegalArgumentException(
					"ChunkedFileToken.getFileName cannot be null");
		if (token.getContentType() == null)
			throw new IllegalArgumentException(
					"ChunkedFileToken.getFileContentType cannot be null");
		// The token key must start with the User's id (and the baseKey if any)
		String userId = getUserId(userInfo);
		if (!token.getKey().startsWith(userId)
				&& token.getKey().indexOf(
						MultipartManagerImpl.FILE_TOKEN_TEMPLATE_SEPARATOR + userId + MultipartManagerImpl.FILE_TOKEN_TEMPLATE_SEPARATOR) == -1)
			throw new UnauthorizedException("The ChunkedFileToken: " + token
					+ " does not belong to User: " + userId);
	}

	@Override
	public UploadDaemonStatus startUploadDeamon(UserInfo userInfo,
			CompleteAllChunksRequest cacf) throws DatastoreException,
			NotFoundException {
		if (cacf == null)
			throw new IllegalArgumentException(
					"CompleteAllChunksRequest cannot be null");
		validateChunkedFileToken(userInfo, cacf.getChunkedFileToken());
		String userId = getUserId(userInfo);
		// Start the daemon
		UploadDaemonStatus status = new UploadDaemonStatus();
		status.setPercentComplete(0.0);
		status.setStartedBy(getUserId(userInfo));
		status.setRunTimeMS(0l);
		status.setState(State.PROCESSING);
		status = uploadDaemonStatusDao.create(status);
		// Create a worker and add it to the pool.
		CompleteUploadWorker worker = new CompleteUploadWorker(uploadDaemonStatusDao, uploadFileDaemonThreadPoolSecondary, status, cacf,
				multipartManager, multipartUploadDaemonTimeoutMS, userId);
		// Get a new copy of the status so we are not returning the same
		// instance that we passed to the worker.
		status = uploadDaemonStatusDao.get(status.getDaemonId());
		// Add this worker the primary pool
		uploadFileDaemonThreadPoolPrimary.submit(worker);
		// Return the status to the caller.
		return status;
	}

	@Override
	public S3FileHandle multipartUploadLocalFile(UserInfo userInfo,
			File fileToUpload, String contentType, ProgressListener listener) {
		String userId = getUserId(userInfo);
		return multipartManager.multipartUploadLocalFile(null, userId,
				fileToUpload, contentType, listener);
	}

	@Override
	public UploadDaemonStatus getUploadDaemonStatus(UserInfo userInfo,
			String daemonId) throws DatastoreException, NotFoundException {
		if (userInfo == null)
			throw new IllegalArgumentException("UserInfo cannot be null");
		if (daemonId == null)
			throw new IllegalArgumentException("DaemonID cannot be null");
		UploadDaemonStatus status = uploadDaemonStatusDao.get(daemonId);
		// Only the user that started the daemon can see the status
		if (!authorizationManager.isUserCreatorOrAdmin(userInfo,
				status.getStartedBy())) {
			throw new UnauthorizedException(
					"Only the user that started the daemon may access the daemon status");
		}
		return status;
	}

	@Override
	public String getRedirectURLForFileHandle(UserInfo userInfo,
			String fileHandleId) throws DatastoreException, NotFoundException {
		if (userInfo == null) {
			throw new IllegalArgumentException("User cannot be null");
		}
		if (fileHandleId == null) {
			throw new IllegalArgumentException("FileHandleId cannot be null");
		}
		FileHandle handle = fileHandleDao.get(fileHandleId);
		// Only the user that created the FileHandle can get the URL directly.
		if (!authorizationManager.isUserCreatorOrAdmin(userInfo,
				handle.getCreatedBy())) {
			throw new UnauthorizedException(
					"Only the user that created the FileHandle can get the URL of the file.");
		}
		return getURLForFileHandle(handle);
	}

	@Override
	@Deprecated
	public List<UploadDestination> getUploadDestinations(UserInfo userInfo, String parentId) throws DatastoreException,
			UnauthorizedException, NotFoundException {
		List<UploadDestinationLocation> uploadDestinationLocations = getUploadDestinationLocations(userInfo, parentId);

		List<UploadDestination> destinations = Lists.newArrayListWithExpectedSize(4);
		for (UploadDestinationLocation uploadDestinationLocation : uploadDestinationLocations) {
			destinations.add(getUploadDestination(userInfo, parentId, uploadDestinationLocation.getStorageLocationId()));
		}
		return destinations;
	}

	@Override
	public List<UploadDestinationLocation> getUploadDestinationLocations(UserInfo userInfo, String parentId) throws DatastoreException,
			NotFoundException {
		UploadDestinationListSetting uploadDestinationsSettings = projectSettingsManager.getProjectSettingForNode(userInfo, parentId,
				ProjectSettingsType.upload, UploadDestinationListSetting.class);

		// make sure there is always one entry
		if (uploadDestinationsSettings == null || uploadDestinationsSettings.getLocations() == null
				|| uploadDestinationsSettings.getLocations().isEmpty()) {
			UploadDestinationLocation uploadDestinationLocation = new UploadDestinationLocation();
			uploadDestinationLocation.setStorageLocationId(DBOStorageLocationDAOImpl.DEFAULT_STORAGE_LOCATION_ID);
			uploadDestinationLocation.setUploadType(UploadType.S3);
			return Collections.<UploadDestinationLocation> singletonList(uploadDestinationLocation);
		} else {
			return projectSettingsManager.getUploadDestinationLocations(userInfo, uploadDestinationsSettings.getLocations());
		}
	}

	@Override
	public UploadDestination getUploadDestination(UserInfo userInfo, String parentId, Long storageLocationId) throws DatastoreException,
			NotFoundException {
		// handle default case
		if (storageLocationId.equals(DBOStorageLocationDAOImpl.DEFAULT_STORAGE_LOCATION_ID)) {
			return DBOStorageLocationDAOImpl.getDefaultUploadDestination();
		}

		StorageLocationSetting storageLocationSetting = storageLocationDAO.get(storageLocationId);

		UploadDestination uploadDestination;

		if (storageLocationSetting instanceof S3StorageLocationSetting) {
			uploadDestination = new S3UploadDestination();
		} else if (storageLocationSetting instanceof ExternalS3StorageLocationSetting) {
			ExternalS3StorageLocationSetting externalS3StorageLocationSetting = (ExternalS3StorageLocationSetting) storageLocationSetting;
			ExternalS3UploadDestination externalS3UploadDestination = new ExternalS3UploadDestination();
			externalS3UploadDestination.setBucket(externalS3StorageLocationSetting.getBucket());
			externalS3UploadDestination.setBaseKey(externalS3StorageLocationSetting.getBaseKey());
			externalS3UploadDestination.setEndpointUrl(externalS3StorageLocationSetting.getEndpointUrl());
			uploadDestination = externalS3UploadDestination;
		} else if (storageLocationSetting instanceof ExternalStorageLocationSetting) {
			String filename = UUID.randomUUID().toString();
			List<EntityHeader> nodePath = nodeManager.getNodePath(userInfo, parentId);
			uploadDestination = createExternalUploadDestination((ExternalStorageLocationSetting) storageLocationSetting,
					nodePath, filename);
		} else {
			throw new IllegalArgumentException("Cannot handle upload destination location setting of type: "
					+ storageLocationSetting.getClass().getName());
		}

		uploadDestination.setStorageLocationId(storageLocationId);
		uploadDestination.setUploadType(storageLocationSetting.getUploadType());
		uploadDestination.setBanner(storageLocationSetting.getBanner());
		return uploadDestination;
	}

	@Override
	public UploadDestination getDefaultUploadDestination(UserInfo userInfo, String parentId) throws DatastoreException, NotFoundException {
		UploadDestinationListSetting uploadDestinationsSettings = projectSettingsManager.getProjectSettingForNode(userInfo, parentId,
				ProjectSettingsType.upload, UploadDestinationListSetting.class);

		// make sure there is always one entry
		Long storageLocationId;
		if (uploadDestinationsSettings == null || uploadDestinationsSettings.getLocations() == null
				|| uploadDestinationsSettings.getLocations().isEmpty()) {
			storageLocationId = DBOStorageLocationDAOImpl.DEFAULT_STORAGE_LOCATION_ID;
		} else {
			storageLocationId = uploadDestinationsSettings.getLocations().get(0);
		}
		return getUploadDestination(userInfo, parentId, storageLocationId);
	}

	private UploadDestination createExternalUploadDestination(ExternalStorageLocationSetting externalUploadDestinationSetting,
			List<EntityHeader> nodePath, String filename) {
		return createExternalUploadDestination(externalUploadDestinationSetting.getUrl(),
				externalUploadDestinationSetting.getSupportsSubfolders(), nodePath, filename);
	}

	private UploadDestination createExternalUploadDestination(String baseUrl, Boolean supportsSubfolders, List<EntityHeader> nodePath,
			String filename) {
		StringBuilder url = new StringBuilder(baseUrl);
		if (url.length() == 0) {
			throw new IllegalArgumentException("The url for the external upload destination setting is empty");
		}
		if (url.charAt(url.length() - 1) != '/') {
			url.append('/');
		}
		// need to add subfolders here if supported
		if (BooleanUtils.isTrue(supportsSubfolders)) {
			if (nodePath.size() > 0) {
				// the first path in the node path is always "root". We don't
				// want that to show up in the file path
				nodePath = nodePath.subList(1, nodePath.size());
			}
			for (EntityHeader node : nodePath) {
				try {
					// we need to url encode, but r client does not like '+' for
					// space. So encode with java encoder and
					// then replace '+' with %20
					url.append(URLEncoder.encode(node.getName(), "UTF-8").replace("+", "%20")).append('/');
				} catch (UnsupportedEncodingException e) {
					// shouldn't happen
					throw new IllegalArgumentException(e.getMessage(), e);
				}
			}
		}
		url.append(filename);
		ExternalUploadDestination externalUploadDestination = new ExternalUploadDestination();
		externalUploadDestination.setUrl(url.toString());
		return externalUploadDestination;
	}

	/**
	 * Extract the file name from the keys
	 * 
	 * @param key
	 * @return
	 */
	public static String extractFileNameFromKey(String key) {
		if (key == null) {
			return null;
		}
		String[] slash = key.split("/");
		if (slash.length > 0) {
			return slash[slash.length - 1];
		}
		return null;
	}
	
	/*
	 * (non-Javadoc)
	 * @see org.sagebionetworks.repo.manager.file.FileHandleManager#createCompressedFileFromString(java.lang.String, java.util.Date, java.lang.String)
	 */
	@Override
	public S3FileHandle createCompressedFileFromString(String createdBy,
			Date modifiedOn, String fileContents) throws UnsupportedEncodingException, IOException {
		return createCompressedFileFromString(createdBy, modifiedOn, fileContents, "application/octet-stream");
	}	
	
	/*
	 * (non-Javadoc)
	 * @see org.sagebionetworks.repo.manager.file.FileHandleManager#createCompressedFileFromString(java.lang.String, java.util.Date, java.lang.String)
	 */
	@Override
	public S3FileHandle createCompressedFileFromString(String createdBy,
			Date modifiedOn, String fileContents, String mimeType) throws UnsupportedEncodingException, IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		FileUtils.writeString(fileContents, DEFAULT_FILE_CHARSET, /*gzip*/true, out);
		byte[] compressedBytes = out.toByteArray();
		ContentType contentType = ContentType.create(mimeType, DEFAULT_FILE_CHARSET);
		return createFileFromByteArray(createdBy, modifiedOn, compressedBytes, null, contentType, GZIP_CONTENT_ENCODING);
	}
	
	@Override
	public S3FileHandle createFileFromByteArray(String createdBy,
				Date modifiedOn, byte[] fileContents, String fileName, ContentType contentType, String contentEncoding) throws UnsupportedEncodingException, IOException {
		// Create the compress string
		ByteArrayInputStream in = new ByteArrayInputStream(fileContents);
		String md5 = MD5ChecksumHelper.getMD5ChecksumForByteArray(fileContents);
		String hexMd5 = BinaryUtils.toBase64(BinaryUtils.fromHex(md5));
		// Upload the file to S3
		if (fileName==null) fileName=DEFAULT_COMPRESSED_FILE_NAME;
		ObjectMetadata meta = new ObjectMetadata();
		meta.setContentType(contentType.toString());
		meta.setContentMD5(hexMd5);
		meta.setContentLength(fileContents.length);
		meta.setContentDisposition(TransferUtils.getContentDispositionValue(fileName));
		if (contentEncoding!=null) meta.setContentEncoding(contentEncoding);
		String key = MultipartManagerImpl.createNewKey(createdBy, fileName, null);
		String bucket = StackConfiguration.getS3Bucket();
		s3Client.putObject(bucket, key, in, meta);
		// Create the file handle
		S3FileHandle handle = new S3FileHandle();
		handle.setBucketName(bucket);
		handle.setKey(key);
		handle.setContentMd5(md5);
		handle.setContentType(meta.getContentType());
		handle.setContentSize(meta.getContentLength());
		handle.setFileName(fileName);
		handle.setCreatedBy(createdBy);
		handle.setCreatedOn(modifiedOn);
		return fileHandleDao.createFile(handle, true);
	}
	
	/**
	 * Retrieves file, decompressing if Content-Encoding indicates that it's gzipped
	 * @param fileHandleId
	 * @return
	 */
	public String downloadFileToString(String fileHandleId) throws IOException {
		URL url;
		try {
			url = new URL(getRedirectURLForFileHandle(fileHandleId));
		} catch (MalformedURLException e) {
			throw new IllegalArgumentException(e.getMessage(), e);
		}

		// Read the file
		try {
			InputStream in = null;
			try {
				URLConnection connection = url.openConnection();
				String contentEncoding = connection.getHeaderField(HttpHeaders.CONTENT_ENCODING);
				String contentTypeString = connection.getHeaderField(HttpHeaders.CONTENT_TYPE);
				Charset charset = ContentTypeUtil.getCharsetFromContentTypeString(contentTypeString);
				in = connection.getInputStream();
				if (contentEncoding!=null && GZIP_CONTENT_ENCODING.equals(contentEncoding)) {
					return FileUtils.readStreamAsString(in, charset, /*gunzip*/true);
				} else {
					return FileUtils.readStreamAsString(in, charset, /*gunzip*/false);
				}
			} finally {
				if (in != null) {
					in.close();
				}
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}		
	}

	@WriteTransaction
	@Override
	public S3FileHandle createNeverUploadedPlaceHolderFileHandle(
			String createdBy, Date modifiedOn, String name) throws IOException {
		if(name == null){
			name = "no-name";
		}
		// This will be the contents of the file.
		byte[] bytes = NEVER_UPLOADED_CONTENTS.getBytes("UTF-8");
		String fileName = name.replaceAll("\\.", "_")+PLACEHOLDER_SUFFIX;
		ByteArrayInputStream in = new ByteArrayInputStream(bytes);
		String md5 = MD5ChecksumHelper.getMD5ChecksumForByteArray(bytes);
		String hexMd5 = BinaryUtils.toBase64(BinaryUtils.fromHex(md5));
		// Upload the file to S3
		ObjectMetadata meta = new ObjectMetadata();
		meta.setContentType("text/plain");
		meta.setContentMD5(hexMd5);
		meta.setContentLength(bytes.length);
		meta.setContentDisposition(TransferUtils.getContentDispositionValue(fileName));
		String key = MultipartManagerImpl.createNewKey(createdBy, fileName, null);
		String bucket = StackConfiguration.getS3Bucket();
		s3Client.putObject(bucket, key, in, meta);
		// Create the file handle
		S3FileHandle handle = new S3FileHandle();
		handle.setBucketName(bucket);
		handle.setKey(key);
		handle.setContentMd5(md5);
		handle.setContentType(meta.getContentType());
		handle.setContentSize(meta.getContentLength());
		handle.setFileName(fileName);
		handle.setCreatedBy(createdBy);
		handle.setCreatedOn(modifiedOn);
		return fileHandleDao.createFile(handle, true);
	}

	@Override
	public S3FileHandle createExternalS3FileHandle(UserInfo userInfo,
			S3FileHandle fileHandle) {
		if (userInfo == null){
			throw new IllegalArgumentException("UserInfo cannot be null");
		}
		if (fileHandle == null){
			throw new IllegalArgumentException("FileHandle cannot be null");
		}
		if(fileHandle.getBucketName() == null){
			throw new IllegalArgumentException("FileHandle.bucket cannot be null");
		}
		if(fileHandle.getKey() == null){
			throw new IllegalArgumentException("FileHandle.key cannot be null");
		}
		if(fileHandle.getStorageLocationId() == null){
			throw new IllegalArgumentException("FileHandle.storageLocationId cannot be null");
		}
		if (fileHandle.getFileName() == null) {
			fileHandle.setFileName(NOT_SET);
		}
		if (fileHandle.getContentType() == null) {
			fileHandle.setContentType(NOT_SET);
		}
		// Lookup the storage location
		StorageLocationSetting sls = storageLocationDAO.get(fileHandle.getStorageLocationId());
		ExternalS3StorageLocationSetting esls = null;
		if(!(sls instanceof ExternalS3StorageLocationSetting)){
			throw new IllegalArgumentException("StorageLocationSetting.id="+fileHandle.getStorageLocationId()+" was not of the expected type: "+ExternalS3StorageLocationSetting.class.getName());
		}
		esls = (ExternalS3StorageLocationSetting) sls;
		if(!fileHandle.getBucketName().equals(esls.getBucket())){
			throw new IllegalArgumentException("The bucket for ExternalS3StorageLocationSetting.id="+fileHandle.getStorageLocationId()+" does not match the provided bucket: "+fileHandle.getBucketName());
		}
		
		/*
		 *  The creation of the ExternalS3StorageLocationSetting already validates that the user has
		 *  permission to update the bucket. So the creator of the storage location is
		 *  the only one that can create an S3FileHandle using that storage location Id. 
		 */
		if(!esls.getCreatedBy().equals(userInfo.getId())){
			throw new UnauthorizedException("Only the creator of ExternalS3StorageLocationSetting.id="+fileHandle.getStorageLocationId()+" can create an external S3FileHandle with storagaeLocationId = "+fileHandle.getStorageLocationId());
		}
		try {
			ObjectMetadata summary = s3Client.getObjectMetadata(fileHandle.getBucketName(), fileHandle.getKey());
			// set content MD5 and lenght if they were not passed in
			if (fileHandle.getContentMd5() == null) {
				fileHandle.setContentMd5(summary.getETag());
			}
			if (fileHandle.getContentSize() == null) {
				fileHandle.setContentSize(summary.getContentLength());
			}
		} catch (Exception e) {
			throw new IllegalArgumentException("Unable to ready metadata for bucket: "+fileHandle.getBucketName()+" key: "+fileHandle.getKey(), e);
		} 
		// set this user as the creator of the file
		fileHandle.setCreatedBy(getUserId(userInfo));
		fileHandle.setCreatedOn(new Date());
		fileHandle.setEtag(UUID.randomUUID().toString());
		// Save the file metadata to the DB.
		return fileHandleDao.createFile(fileHandle);
	}

	@Override
	public S3FileHandle createS3FileHandleCopy(UserInfo userInfo, String handleIdToCopyFrom, String fileName, String contentType) {
		ValidateArgument.required(userInfo, "UserInfo");
		ValidateArgument.required(handleIdToCopyFrom, "handleIdToCopyFrom");
		ValidateArgument.requirement(StringUtils.isNotEmpty(fileName) || StringUtils.isNotEmpty(contentType),
				"Either the fileName or the contentType needs to be set");

		FileHandle originalFileHandle = fileHandleDao.get(handleIdToCopyFrom);
		ValidateArgument.requireType(originalFileHandle, S3FileHandle.class, "file handle to copy from");
		S3FileHandle newS3FileHandle = (S3FileHandle) originalFileHandle;

		/*
		 * Only the creator of the original file handle can create a copy
		 */
		// Is the user authorized?
		if (!authorizationManager.canAccessRawFileHandleByCreator(userInfo, handleIdToCopyFrom, originalFileHandle.getCreatedBy())
				.getAuthorized()) {
			throw new UnauthorizedException("Only the creator of a file handle can create a copy. File handle id=" + handleIdToCopyFrom
					+ " is not owned by you");
		}

		newS3FileHandle.setId(null);
		newS3FileHandle.setEtag(null);
		newS3FileHandle.setCreatedBy(getUserId(userInfo));
		newS3FileHandle.setCreatedOn(new Date());

		boolean needsNewPreview = false;
		if (StringUtils.isNotEmpty(fileName)) {
			if (!StringUtils.equals(FilenameUtils.getExtension(fileName), FilenameUtils.getExtension(newS3FileHandle.getFileName()))) {
				needsNewPreview = true;
			}
			newS3FileHandle.setFileName(fileName);
		}
		if (StringUtils.isNotEmpty(contentType)) {
			if (!StringUtils.equals(contentType, newS3FileHandle.getContentType())) {
				needsNewPreview = true;
			}
			newS3FileHandle.setContentType(contentType);
		}
		if (needsNewPreview) {
			newS3FileHandle.setPreviewId(null);
		}
		// Save the file metadata to the DB.
		return fileHandleDao.createFile(newS3FileHandle);
	}
}
