package org.sagebionetworks.repo.service;

import org.sagebionetworks.repo.model.metadata.ListMetadataTaskRequest;
import org.sagebionetworks.repo.model.metadata.ListMetadataTaskResponse;
import org.sagebionetworks.repo.model.metadata.MetadataTask;
import org.sagebionetworks.repo.web.NotFoundException;

public interface MetadataTaskService {

    MetadataTask createMetadataTask(Long userId, MetadataTask toCreate);


    MetadataTask updateMetadataTask(Long userId, MetadataTask toUpdate)
            throws NotFoundException;

    MetadataTask getMetadataTask(Long userId, String taskId);

    void deleteMetadataTask(Long userId, String taskId) throws NotFoundException;

    ListMetadataTaskResponse getMetadataTasks(Long userId, ListMetadataTaskRequest request);
}
