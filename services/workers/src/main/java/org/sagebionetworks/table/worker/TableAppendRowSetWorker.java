package org.sagebionetworks.table.worker;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.sagebionetworks.common.util.progress.ProgressCallback;
import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.manager.asynch.AsynchJobStatusManager;
import org.sagebionetworks.repo.manager.asynch.AsynchJobUtils;
import org.sagebionetworks.repo.manager.table.AppendableTableManager;
import org.sagebionetworks.repo.manager.table.AppendableTableManagerFactory;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.asynch.AsynchronousJobStatus;
import org.sagebionetworks.repo.model.table.AppendableRowSet;
import org.sagebionetworks.repo.model.table.AppendableRowSetRequest;
import org.sagebionetworks.repo.model.table.PartialRowSet;
import org.sagebionetworks.repo.model.table.RowReferenceSet;
import org.sagebionetworks.repo.model.table.RowReferenceSetResults;
import org.sagebionetworks.repo.model.table.RowSet;
import org.sagebionetworks.workers.util.aws.message.MessageDrivenRunner;
import org.sagebionetworks.workers.util.aws.message.RecoverableMessageException;
import org.springframework.beans.factory.annotation.Autowired;

import com.amazonaws.services.sqs.model.Message;
/**
 * This worker appends partial row sets to a table.
 * 
 * @author jmhill
 *
 */
public class TableAppendRowSetWorker implements MessageDrivenRunner {

	static private Logger log = LogManager.getLogger(TableAppendRowSetWorker.class);

	@Autowired
	private AsynchJobStatusManager asynchJobStatusManager;
	@Autowired
	private AppendableTableManagerFactory appendableTableManagerFactory;
	@Autowired
	private UserManager userManger;


	/**
	 * Process a single message
	 * @param message
	 * @return
	 * @throws Throwable 
	 */
	@Override
	public void run(ProgressCallback<Void> progressCallback, Message message)
			throws RecoverableMessageException, Exception {
		// First read the body
		try {
			processStatus(progressCallback, message);
		} catch (Throwable e) {
			log.error("Failed", e);
		}
	}

	/**
	 * @param status
	 * @throws Throwable 
	 */
	public void processStatus(final ProgressCallback<Void> progressCallback, final Message message) throws Throwable {
		AsynchronousJobStatus status = asynchJobStatusManager.lookupJobStatus(message.getBody());
		try{
			UserInfo user = userManger.getUserInfo(status.getStartedByUserId());
			AppendableRowSetRequest body = AsynchJobUtils.extractRequestBody(status, AppendableRowSetRequest.class);
			AppendableRowSet appendSet = body.getToAppend();
			if(appendSet == null){
				throw new IllegalArgumentException("ToAppend cannot be null");
			}
			if(appendSet.getTableId() == null){
				throw new IllegalArgumentException("Table ID cannot be null");
			}
			String tableId = appendSet.getTableId();
			long progressCurrent = 0L;
			long progressTotal = 100L;
			// Start the progress
			asynchJobStatusManager.updateJobProgress(status.getJobId(), progressCurrent, progressTotal, "Starting...");
			ProgressCallback<Long> rowCallback = new ProgressCallback<Long>() {
				@Override
				public void progressMade(Long progress) {
					progressCallback.progressMade(null);
				}
			};
			// get the manager to be used for this type.
			AppendableTableManager manager = appendableTableManagerFactory.getManagerForEntity(tableId);
			// Do the work
			RowReferenceSet results = null;
			if(appendSet instanceof PartialRowSet){
				PartialRowSet partialRowSet = (PartialRowSet) appendSet;
				results =  manager.appendPartialRows(user, tableId, partialRowSet, rowCallback);
			}else if(appendSet instanceof RowSet){
				RowSet rowSet = (RowSet)appendSet;
				results = manager.appendRows(user, tableId, rowSet, rowCallback);
			}else{
				throw new IllegalArgumentException("Unknown RowSet type: "+appendSet.getClass().getName());
			}

			RowReferenceSetResults  rrsr = new RowReferenceSetResults();
			rrsr.setRowReferenceSet(results);
			asynchJobStatusManager.setComplete(status.getJobId(), rrsr);
		}catch(Throwable e){
			// Record the error
			asynchJobStatusManager.setJobFailed(status.getJobId(), e);
			throw e;
		}
	}
}
