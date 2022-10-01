package org.sagebionetworks.asynchronous.workers.concurrent;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import org.sagebionetworks.common.util.progress.ProgressCallback;
import org.sagebionetworks.database.semaphore.CountingSemaphore;
import org.sagebionetworks.repo.model.StackStatusDao;
import org.sagebionetworks.util.ValidateArgument;
import org.sagebionetworks.workers.util.aws.message.MessageDrivenRunner;
import org.sagebionetworks.workers.util.aws.message.RecoverableMessageException;
import org.springframework.stereotype.Repository;

import com.amazonaws.services.sqs.AmazonSQSClient;
import com.amazonaws.services.sqs.model.ChangeMessageVisibilityRequest;
import com.amazonaws.services.sqs.model.DeleteMessageRequest;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;

@Repository
public class ConcurrentSingletonImpl implements ConcurrentSingleton {

	private final CountingSemaphore countingSemaphore;
	private final ExecutorService executorService;
	private final AmazonSQSClient amazonSQSClient;
	private final StackStatusDao stackStatusDao;

	public ConcurrentSingletonImpl(CountingSemaphore countingSemaphore, AmazonSQSClient amazonSQSClient,
			StackStatusDao stackStatusDao) {
		super();
		this.countingSemaphore = countingSemaphore;
		this.amazonSQSClient = amazonSQSClient;
		this.stackStatusDao = stackStatusDao;
		/*
		 * Note: We do not use a fix sized thread pool because we do not know how many
		 * threads will be needed for all of the workers on this machine. Each SQS
		 * message is received from a queue prior to being submitted to this thread pool
		 * for processing. Once a message is received it must be processed immediately.
		 * If the message processing is delayed due to a lack of an available thread on
		 * this machine then a live-lock scenario will be created. In such a scenario,
		 * other machines with available threads will be idle, while the held messages
		 * on this machine waits for an available threads. <p> The cached thread pool
		 * allows us guarantee that every messages received has a thread immediately
		 * available, while at it will reuse threads already allocated (until they
		 * expire).
		 */
		this.executorService = Executors.newCachedThreadPool();
	}

	@Override
	public boolean isStackAvailableForWrite() {
		return stackStatusDao.isStackReadWrite();
	}

	@Override
	public long getCurrentTimeMS() {
		return System.currentTimeMillis();
	}

	@Override
	public void sleep(long sleepTimeMS) throws InterruptedException {
		Thread.sleep(sleepTimeMS);
	}

	@Override
	public String getSqsQueueUrl(String queueName) {
		ValidateArgument.required(queueName, "queueName");
		return amazonSQSClient.getQueueUrl(queueName).getQueueUrl();
	}

	@Override
	public void runWithSemaphoreLock(String lockKey, int lockTimeoutSec, int maxLockCount, ProgressCallback callback,
			Runnable runner) {
		ValidateArgument.required(lockKey, "lockKey");
		ValidateArgument.required(callback, "callback");
		ValidateArgument.required(runner, "runner");

		String token = countingSemaphore.attemptToAcquireLock(lockKey, lockTimeoutSec, maxLockCount);
		if (token == null) {
			return;
		}
		callback.addProgressListener(() -> {
			countingSemaphore.refreshLockTimeout(lockKey, token, lockTimeoutSec);
		});
		try {
			runner.run();
		} finally {
			countingSemaphore.releaseLock(lockKey, token);
		}
	}

	@Override
	public List<WorkerJob> pollForMessagesAndStartJobs(String queueUrl, int maxNumberOfMessages,
			int messageVisibilityTimeoutSec, MessageDrivenRunner worker) {
		ValidateArgument.required(queueUrl, "queueUrl");
		ValidateArgument.required(worker, "worker");
		ValidateArgument.requirement(maxNumberOfMessages >= 1,
				"maxNumberOfMessages must be greater than or equals to 1.");
		ValidateArgument.requirement(maxNumberOfMessages <= 10,
				"maxNumberOfMessages must be less than or equals to 10.");
		ValidateArgument.requirement(messageVisibilityTimeoutSec >= 10,
				"messageVisibilityTimeoutSec must be greater than or equals to 10.");

		// Poll for the requested number of messages.
		List<Message> messages = amazonSQSClient.receiveMessage(new ReceiveMessageRequest().withQueueUrl(queueUrl)
				.withWaitTimeSeconds(0).withMaxNumberOfMessages(maxNumberOfMessages)
				.withVisibilityTimeout(messageVisibilityTimeoutSec)).getMessages();
		// For each message start a new job.
		return messages.stream().map((message) -> {
			return startWorkerJob(queueUrl, messageVisibilityTimeoutSec, worker, message);
		}).collect(Collectors.toList());
	}

	/**
	 * For the given message, submit a new worker instance to the thread pool.
	 * 
	 * @param queueUrl
	 * @param messageVisibilityTimeoutSec
	 * @param worker
	 * @param message
	 * @return
	 */
	WorkerJob startWorkerJob(String queueUrl, int messageVisibilityTimeoutSec, MessageDrivenRunner worker,
			Message message) {
		ConcurrentProgressCallback callback = new ConcurrentProgressCallback(messageVisibilityTimeoutSec);
		callback.addProgressListener(() -> {
			amazonSQSClient.changeMessageVisibility(new ChangeMessageVisibilityRequest().withQueueUrl(queueUrl)
					.withQueueUrl(message.getReceiptHandle()).withVisibilityTimeout(messageVisibilityTimeoutSec));
		});
		Future<Void> future = executorService.submit(() -> {
			boolean deleteMessage = true;
			try {
				worker.run(callback, message);
			} catch (RecoverableMessageException e) {
				deleteMessage = false;
				amazonSQSClient.changeMessageVisibility(new ChangeMessageVisibilityRequest().withQueueUrl(queueUrl)
						.withQueueUrl(message.getReceiptHandle()).withVisibilityTimeout(5));
			} finally {
				if (deleteMessage) {
					amazonSQSClient.deleteMessage(new DeleteMessageRequest().withQueueUrl(queueUrl)
							.withReceiptHandle(message.getReceiptHandle()));
				}
			}
			return null;
		});
		return new WorkerJob(future, callback);
	}

}