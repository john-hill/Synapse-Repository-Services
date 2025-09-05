package org.sagebionetworks.repo.service;

import org.sagebionetworks.repo.model.metadata.ListMetadataTaskRequest;
import org.sagebionetworks.repo.model.metadata.ListMetadataTaskResponse;
import org.sagebionetworks.repo.model.metadata.MetadataTask;
import org.sagebionetworks.repo.web.NotFoundException;
import org.springframework.stereotype.Service;

@Service
public class MetadataTaskServiceImpl implements MetadataTaskService {
    @Override
    public MetadataTask createMetadataTask(Long userId, MetadataTask toCreate) {
        // TODO: Stub
        throw new NotFoundException("The service you requested has not yet been implemented");
    }

    @Override
    public MetadataTask updateMetadataTask(Long userId, MetadataTask toUpdate) throws NotFoundException {
        // TODO: Stub
        throw new NotFoundException("The service you requested has not yet been implemented");
    }

    @Override
    public MetadataTask getMetadataTask(Long userId, String taskId) {
        // TODO: Stub
        throw new NotFoundException("The service you requested has not yet been implemented");
    }

    @Override
    public void deleteMetadataTask(Long userId, String taskId) throws NotFoundException {
        // TODO: Stub
        throw new NotFoundException("The service you requested has not yet been implemented");

    }

    @Override
    public ListMetadataTaskResponse getMetadataTasks(Long userId, ListMetadataTaskRequest request) {
        // TODO: Stub
        throw new NotFoundException("The service you requested has not yet been implemented");
    }
}
