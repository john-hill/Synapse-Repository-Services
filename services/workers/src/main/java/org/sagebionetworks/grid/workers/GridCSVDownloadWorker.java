package org.sagebionetworks.grid.workers;

import java.io.File;
import java.io.FileWriter;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.sagebionetworks.repo.manager.file.FileHandleManager;
import org.sagebionetworks.repo.manager.file.LocalFileUploadRequest;
import org.sagebionetworks.repo.manager.grid.internal.replica.export.GridReplicaCsvExporter;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dao.asynch.AsyncJobProgressCallback;
import org.sagebionetworks.repo.model.file.S3FileHandle;
import org.sagebionetworks.repo.model.grid.DownloadFromGridRequest;
import org.sagebionetworks.repo.model.grid.DownloadFromGridResult;
import org.sagebionetworks.table.cluster.utils.CSVUtils;
import org.sagebionetworks.table.worker.CSVWriterProvider;
import org.sagebionetworks.table.worker.ProgressingCSVWriterStream;
import org.sagebionetworks.util.Clock;
import org.sagebionetworks.worker.AsyncJobRunner;
import org.sagebionetworks.workers.util.aws.message.RecoverableMessageException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import au.com.bytecode.opencsv.CSVWriter;

/**
 * This worker will stream the contents of a grid to a local CSV file and upload the file
 * to S3 as a FileHandle.
 */
@Service
public class GridCSVDownloadWorker implements AsyncJobRunner<DownloadFromGridRequest, DownloadFromGridResult> {

	static private Logger log = LogManager.getLogger(GridCSVDownloadWorker.class);

	@Autowired
	private GridReplicaCsvExporter gridReplicaCsvExporter;
	@Autowired
	private FileHandleManager fileHandleManager;
	@Autowired
	private Clock clock;
	@Autowired
	private CSVWriterProvider csvWriterProvider;
	
	@Override
	public Class<DownloadFromGridRequest> getRequestType() {
		return DownloadFromGridRequest.class;
	}
	
	@Override
	public Class<DownloadFromGridResult> getResponseType() {
		return DownloadFromGridResult.class;
	}
	
	@Override
	public DownloadFromGridResult run(String jobId, UserInfo user, DownloadFromGridRequest request, AsyncJobProgressCallback jobProgressCallback) throws RecoverableMessageException, Exception {
		String fileName = "Job-"+jobId;
		File temp = null;
		try {
			// Since each row must first be read from the database then uploaded to S3
			long totalProgress = 100;
			long currentProgress = 0;
			// The CSV data will first be written to this file.
			temp = File.createTempFile(fileName, "." + CSVUtils.guessExtension(
					request.getCsvTableDescriptor() == null ? null : request.getCsvTableDescriptor().getSeparator()));
			DownloadFromGridResult result;
			try(CSVWriter writer = csvWriterProvider.createWriter(new FileWriter(temp), request.getCsvTableDescriptor());){
				// this object will update the progress of both the job and refresh the timeout on the message as rows are read from the DB.
				ProgressingCSVWriterStream stream = new ProgressingCSVWriterStream(writer, jobProgressCallback, currentProgress, totalProgress, clock);
				result =  gridReplicaCsvExporter.exportGridAsCsv(user, request, stream);
			}

			// At this point we have the entire CSV written to a local file.
			// Upload the file to S3 can create the filehandle.
            jobProgressCallback.updateProgress("Finished writing CSV file. Uploading to S3...", totalProgress/2, totalProgress);
			String contentType = CSVUtils.guessContentType(request
					.getCsvTableDescriptor() == null ? null : request.getCsvTableDescriptor().getSeparator());
			String requestFileName = request.getFileName() == null ? null : request.getFileName();
			S3FileHandle fileHandle = fileHandleManager.uploadLocalFile(new LocalFileUploadRequest().withUserId(user.getId().toString()).withFileToUpload(temp).withContentType(contentType)
					.withFileName(requestFileName));
			result.setResultsFileHandleId(fileHandle.getId());
			return result;
		} catch (RecoverableMessageException e) {
			throw e;
		} catch(Exception e){
			log.error("Worker Failed", e);
			throw new RuntimeException(e);
		} finally {
			if(temp != null){
				temp.delete();
			}
		}
	}

}
