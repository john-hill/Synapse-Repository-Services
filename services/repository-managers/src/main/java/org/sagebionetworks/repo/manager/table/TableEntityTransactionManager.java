package org.sagebionetworks.repo.manager.table;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Callable;

import org.sagebionetworks.common.util.progress.ProgressCallback;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.table.TableUnavailableException;
import org.sagebionetworks.repo.model.table.TableUpdateRequest;
import org.sagebionetworks.repo.model.table.TableUpdateResponse;
import org.sagebionetworks.repo.model.table.TableUpdateTransactionRequest;
import org.sagebionetworks.repo.model.table.TableUpdateTransactionResponse;
import org.sagebionetworks.util.ValidateArgument;
import org.sagebionetworks.workers.util.aws.message.RecoverableMessageException;
import org.sagebionetworks.workers.util.semaphore.LockUnavilableException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

public class TableEntityTransactionManager implements TableTransactionManager {
	
	private static final int EXCLUSIVE_LOCK_TIMEOUT_MS = 5*1000*60;
	
	@Autowired
	TableManagerSupport tableManagerSupport;
	@Autowired
	TransactionTemplate readCommitedTransactionTemplate;
	@Autowired
	TableEntityManager tableEntityManager;
	@Autowired
	TableIndexConnectionFactory tableIndexConnectionFactory;

	@Override
	public TableUpdateTransactionResponse updateTableWithTransaction(
			final ProgressCallback<Void> progressCallback, final UserInfo userInfo,
			final TableUpdateTransactionRequest request)
			throws RecoverableMessageException, TableUnavailableException {
		
		ValidateArgument.required(progressCallback, "callback");
		ValidateArgument.required(userInfo, "userInfo");
		TableTransactionUtils.validateRequest(request);
		String tableId = request.getEntityId();
		// Validate the user has permission to edit the table before locking.
		tableManagerSupport.validateTableWriteAccess(userInfo, tableId);
		try {
			return tableManagerSupport.tryRunWithTableExclusiveLock(progressCallback, tableId, EXCLUSIVE_LOCK_TIMEOUT_MS, new Callable<TableUpdateTransactionResponse>() {

				@Override
				public TableUpdateTransactionResponse call() throws Exception {
					return updateTableWithTransactionWithExclusiveLock(userInfo, request);
				}
			});
		}catch (TableUnavailableException e) {
			throw e;
		}catch (LockUnavilableException e) {
			throw e;
		}catch (RecoverableMessageException e) {
			throw e;
		}catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
	
	/**
	 * This method should only be called from while holding the lock on the table.
	 * @param callback
	 * @param userInfo
	 * @param request
	 * @return
	 */
	TableUpdateTransactionResponse updateTableWithTransactionWithExclusiveLock(final UserInfo userInfo,
			final TableUpdateTransactionRequest request) {
		/*
		 * Request validation can take a long time and may not involve the primary database.
		 * Therefore the primary transaction is not started until after validation succeeds.
		 * A transaction template is used to allow for finer control of the transaction boundary.
		 */
		validateUpdateRequests(userInfo, request);
		// the update is valid so the primary transaction can be started.
		return readCommitedTransactionTemplate.execute(new TransactionCallback<TableUpdateTransactionResponse>() {

			@Override
			public TableUpdateTransactionResponse doInTransaction(
					TransactionStatus status) {
				return doIntransactionUpdateTable(status, userInfo, request);
			}
		} );
	}


	/**
	 * Validate the passed update request.
	 * @param callback
	 * @param userInfo
	 * @param request
	 */
	void validateUpdateRequests(UserInfo userInfo, TableUpdateTransactionRequest request) {

		// Determine if a temporary table is needed to validate any of the requests.
		boolean isTemporaryTableNeeded = isTemporaryTableNeeded(request);
		
		// setup a temporary table if needed.
		if(isTemporaryTableNeeded){
			String tableId = request.getEntityId();
			TableIndexManager indexManager = tableIndexConnectionFactory.connectToTableIndex(tableId);
			indexManager.createTemporaryTableCopy(tableId);
			try{
				// validate while the temp table exists.
				validateEachUpdateRequest(userInfo, request, indexManager);
			}finally{
				indexManager.deleteTemporaryTableCopy(tableId);
			}
		}else{
			// we do not need a temporary copy to validate this request.
			validateEachUpdateRequest(userInfo, request, null);
		}
	}


	/**
	 * Validate each update request.
	 * @param callback
	 * @param userInfo
	 * @param request
	 * @param indexManager
	 */
	void validateEachUpdateRequest(UserInfo userInfo, TableUpdateTransactionRequest request,
			TableIndexManager indexManager) {
		for(TableUpdateRequest change: request.getChanges()){
			tableEntityManager.validateUpdateRequest(userInfo, change, indexManager);
		}
	}

	/**
	 * Is a temporary table needed to validate any of the changes for the given request.
	 * 
	 * @param callback
	 * @param request
	 * @return
	 */
	boolean isTemporaryTableNeeded(	TableUpdateTransactionRequest request) {
		for(TableUpdateRequest change: request.getChanges()){
			boolean tempNeeded = tableEntityManager.isTemporaryTableNeededToValidate(change);
			if(tempNeeded){
				return true;
			}
		}
		return false;
	}
	
	/**
	 * Called after the update has been validated and from within a transaction.
	 * 
	 * @param status
	 * @param callback
	 * @param userInfo
	 * @param request
	 * @return
	 */
	TableUpdateTransactionResponse doIntransactionUpdateTable(TransactionStatus status,
			UserInfo userInfo,
			TableUpdateTransactionRequest request) {
		// execute each request
		List<TableUpdateResponse> results = new LinkedList<TableUpdateResponse>();
		TableUpdateTransactionResponse response = new TableUpdateTransactionResponse();
		response.setResults(results);
		for(TableUpdateRequest change: request.getChanges()){
			TableUpdateResponse changeResponse = tableEntityManager.updateTable(userInfo, change);
			results.add(changeResponse);
		}
		return response;
	}

}
