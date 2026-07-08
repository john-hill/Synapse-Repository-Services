package org.sagebionetworks.repo.model.dbo.curation;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.sagebionetworks.repo.model.curation.CurationTask;
import org.sagebionetworks.repo.model.curation.TaskBundle;
import org.sagebionetworks.repo.model.curation.TaskState;
import org.sagebionetworks.repo.model.curation.TaskStatus;

public interface CurationTaskDao {
    CurationTask createCurationTask(Long userId, CurationTask toCreate);

    CurationTask updateCurationTask(Long userId, CurationTask toUpdate);

    Optional<CurationTask> getCurationTask(Long taskId);

    void deleteCurationTask(Long taskId);

    List<CurationTask> getCurationTasks(Long projectId, long limit, long offset);

    TaskStatus getTaskStatus(Long taskId);

    TaskStatus updateTaskStatus(Long userId, Long taskId, TaskStatus statusUpdate);

    /**
     * Updates task status and explicitly clears the dueDate to null.
     * Note: This method must be used to clear dueDate, as omitting the dueDate field in a normal
     * updateTaskStatus call will preserve the existing value rather than clearing it.
     *
     * @param userId the user performing the update
     * @param taskId the task ID
     * @param statusUpdate the status update (dueDate field is ignored)
     * @return the updated task status with dueDate set to null
     */
    TaskStatus updateTaskStatusAndClearDueDate(Long userId, Long taskId, TaskStatus statusUpdate);

    /**
     * Clears the activeSessionId from the execution details of the given task, if execution details exist.
     * Must be called within an existing write transaction.
     */
    void clearActiveSessionId(Long taskId);

    List<TaskBundle> getCurationTaskBundles(List<Long> projectIds, List<Long> assigneeIds,
            List<TaskState> stateFilter, List<Long> taskIds, long limit, long offset);

    Set<Long> getDistinctProjectIds();

}
