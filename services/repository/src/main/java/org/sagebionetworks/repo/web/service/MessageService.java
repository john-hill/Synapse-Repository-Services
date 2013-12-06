package org.sagebionetworks.repo.web.service;

import java.util.List;

import org.sagebionetworks.repo.model.PaginatedResults;
import org.sagebionetworks.repo.model.message.MessageBundle;
import org.sagebionetworks.repo.model.message.MessageRecipientSet;
import org.sagebionetworks.repo.model.message.MessageSortBy;
import org.sagebionetworks.repo.model.message.MessageStatus;
import org.sagebionetworks.repo.model.message.MessageStatusType;
import org.sagebionetworks.repo.model.message.MessageToUser;
import org.sagebionetworks.repo.web.NotFoundException;

public interface MessageService {

	public MessageToUser create(Long username, MessageToUser toCreate)
			throws NotFoundException;

	public PaginatedResults<MessageBundle> getInbox(Long username,
			List<MessageStatusType> inclusionFilter, MessageSortBy sortBy,
			boolean descending, long limit, long offset, String urlPath)
			throws NotFoundException;

	public PaginatedResults<MessageToUser> getOutbox(Long username,
			MessageSortBy sortBy, boolean descending, long limit, long offset,
			String urlPath) throws NotFoundException;

	public MessageToUser getMessage(Long username, String messageId)
			throws NotFoundException;

	public MessageToUser forwardMessage(Long username, String messageId,
			MessageRecipientSet recipients) throws NotFoundException;

	public PaginatedResults<MessageToUser> getConversation(Long username,
			String messageId, MessageSortBy sortBy, boolean descending,
			long limit, long offset, String urlPath)
			throws NotFoundException;

	public void updateMessageStatus(Long username, MessageStatus status)
			throws NotFoundException;
	
	public void deleteMessage(Long username, String messageId)
			throws NotFoundException;

}
