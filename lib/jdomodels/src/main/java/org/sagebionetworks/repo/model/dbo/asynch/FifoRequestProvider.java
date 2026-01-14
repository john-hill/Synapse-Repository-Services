package org.sagebionetworks.repo.model.dbo.asynch;

import org.sagebionetworks.repo.model.asynch.AsynchronousRequestBody;

/**
 * Provides extra metadata needed to push messages to FIFO (first-in-first-out)
 * queues for Asynchronous Jobs.
 * 
 * @param <T>
 */
public interface FifoRequestProvider<T extends AsynchronousRequestBody> {

	/**
	 * See: <a href=
	 * "https://docs.aws.amazon.com/AWSSimpleQueueService/latest/APIReference/API_SendMessage.html#SQS-SendMessage-request-MessageGroupId">MessageGroupId</>
	 * 
	 * @param requestBody
	 * @return
	 */
	String getMessageDeduplicationId(T requestBody);

	/**
	 * See: <a href=
	 * "https://docs.aws.amazon.com/AWSSimpleQueueService/latest/APIReference/API_SendMessage.html#SQS-SendMessage-request-MessageDeduplicationId">MessageDeduplicationId</>
	 * 
	 * 
	 */
	String getMessageGroupId(T requestBody);

}
