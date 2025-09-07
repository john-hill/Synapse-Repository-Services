package org.sagebionetworks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.URISyntaxException;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.client.SynapseClient;
import org.sagebionetworks.client.exceptions.SynapseException;
import org.sagebionetworks.repo.model.Entity;
import org.sagebionetworks.repo.model.Folder;
import org.sagebionetworks.repo.model.Project;
import org.sagebionetworks.repo.model.RecordSet;
import org.sagebionetworks.repo.model.curation.CurationTask;
import org.sagebionetworks.repo.model.curation.ListCurationTaskRequest;
import org.sagebionetworks.repo.model.curation.ListCurationTaskResponse;
import org.sagebionetworks.repo.model.curation.metadata.FileBasedMetadataTaskProperties;
import org.sagebionetworks.repo.model.curation.metadata.RecordBasedMetadataTaskProperties;
import org.sagebionetworks.repo.model.table.EntityView;

@ExtendWith(ITTestExtension.class)
public class ITMetadataTaskControllerTest {
    private List<Entity> entitiesToDelete;

    private final SynapseClient synapse;

    public ITMetadataTaskControllerTest(SynapseClient synapse) {
        this.synapse = synapse;
    }

    Project project;
    Folder folder;
    EntityView view;
    RecordSet recordSet;

    @BeforeEach
    public void before() throws SynapseException {
        entitiesToDelete = new LinkedList<Entity>();

        // Set up project, folder, file view, and record set
        project = synapse.createEntity(new Project().setName("ITGridControllerTest" + UUID.randomUUID()));
        entitiesToDelete.add(project);

        folder = synapse.createEntity(new Folder().setName("folder").setParentId(project.getId()));
        entitiesToDelete.add(folder);

        view = synapse.createEntity(new EntityView().setName("view").setParentId(project.getId()));
        entitiesToDelete.add(view);

        recordSet = synapse.createEntity(new RecordSet().setName("record set").setParentId(folder.getId()));
        entitiesToDelete.add(recordSet);
    }

    @AfterEach
    public void after() throws Exception {
        for (Entity entity : entitiesToDelete) {
            synapse.deleteEntity(entity);
        }
    }

    @Test
    public void testMetadataTaskCRUD() throws AssertionError, SynapseException, URISyntaxException, InterruptedException {

        CurationTask fbTask = new CurationTask()
                .setDataType("fastq: file-based")
                .setInstructions("upload to the folder and annotate with the view")
                .setTaskProperties(
                        new FileBasedMetadataTaskProperties()
                                .setFileViewId(view.getId())
                                .setUploadFolderId(recordSet.getId())
                );

        CurationTask rbTask = new CurationTask()
                .setDataType("fastq: record-based")
                .setInstructions("add data to the recordset")
                .setTaskProperties(
                        new RecordBasedMetadataTaskProperties().setRecordSetId(recordSet.getId())
                );

        // call under test - create
        fbTask = synapse.createCurationTask(fbTask);
        rbTask = synapse.createCurationTask(rbTask);

        assertNotNull(fbTask.getTaskId());
        assertNotNull(rbTask.getTaskId());

        // call under test - get
        assertEquals(fbTask, synapse.getMetadataTask(fbTask.getTaskId()));
        assertEquals(rbTask, synapse.getMetadataTask(rbTask.getTaskId()));

        // call under test - get page
        ListCurationTaskResponse response = synapse.listMetadataTasks(new ListCurationTaskRequest().setProjectId(project.getId()));
        assertEquals(2, response.getPage().size());
        assertNull(response.getNextPageToken());
        assertEquals(fbTask, response.getPage().get(0));
        assertEquals(rbTask, response.getPage().get(1));

        fbTask.setInstructions("new instructions for file-based task");
        rbTask.setInstructions("new instructions for record-based task");

        // call under test - update
        fbTask = synapse.updateMetadataTask(fbTask);
        rbTask = synapse.updateMetadataTask(rbTask);

        assertEquals("new instructions for file-based task", fbTask.getInstructions());
        assertEquals("new instructions for record-based task", rbTask.getInstructions());

        // call under test - delete
        synapse.deleteMetadataTask(fbTask.getTaskId());
        synapse.deleteMetadataTask(rbTask.getTaskId());

        response = synapse.listMetadataTasks(new ListCurationTaskRequest().setProjectId(project.getId()));
        assertEquals(0, response.getPage().size());
        assertNull(response.getNextPageToken());
    }

}
