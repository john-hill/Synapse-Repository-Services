package org.sagebionetworks.repo.model.dbo.metadata.task;

import java.util.List;
import java.util.Optional;

import org.sagebionetworks.repo.model.metadata.MetadataTask;

public interface MetadataTaskDao {
    MetadataTask createMetadataTask(Long userId, MetadataTask toCreate);

    MetadataTask updateMetadataTask(Long userId, MetadataTask toUpdate);

    Optional<MetadataTask> getMetadataTask(String taskId);

    void deleteMetadataTask(String taskId);

    List<MetadataTask> getMetadataTasks(Long projectId, long limit, long offset);

}
