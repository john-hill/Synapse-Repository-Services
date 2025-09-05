package org.sagebionetworks.repo.model.dbo.metadata.task;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.junit.After;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.repo.model.ConflictingUpdateException;
import org.sagebionetworks.repo.model.EntityType;
import org.sagebionetworks.repo.model.Node;
import org.sagebionetworks.repo.model.NodeDAO;
import org.sagebionetworks.repo.model.UserGroup;
import org.sagebionetworks.repo.model.UserGroupDAO;
import org.sagebionetworks.repo.model.jdo.KeyFactory;
import org.sagebionetworks.repo.model.metadata.MetadataTask;
import org.sagebionetworks.repo.web.NotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.sagebionetworks.repo.model.metadata.RecordBasedMetadataTask;

import org.sagebionetworks.repo.model.metadata.FileBasedMetadataTask;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = {"classpath:jdomodels-test-context.xml"})
class MetadataTaskDaoAutowireTest {

    // Update
    @Autowired
    NodeDAO nodeDao;

    @Autowired
    UserGroupDAO userGroupDAO;

    @Autowired
    MetadataTaskDao dao;

    private Long userId;
    private Node project1;
    private Node project2;
    private Node recordSet;
    private Node fileView;

    List<String> nodesToDelete;

    @BeforeEach
    public void before() {
        nodesToDelete = new ArrayList<>();

        UserGroup user = new UserGroup();
        user.setIsIndividual(true);
        user.setCreationDate(new Date());
        userId = userGroupDAO.create(user);


        Node project = new Node();
        project.setName("project1");
        project.setNodeType(EntityType.project);
        project.setCreatedByPrincipalId(userId);
        project.setCreatedOn(new Date());
        project.setModifiedByPrincipalId(userId);
        project.setModifiedOn(new Date());
        project1 = nodeDao.createNewNode(project);
        nodesToDelete.add(project1.getId());

        project.setName("project2");
        project2 = nodeDao.createNewNode(project);
        nodesToDelete.add(project2.getId());

        fileView = new Node();
        fileView.setName("fileView");
        fileView.setNodeType(EntityType.entityview);
        fileView.setCreatedByPrincipalId(userId);
        fileView.setCreatedOn(new Date());
        fileView.setModifiedByPrincipalId(userId);
        fileView.setModifiedOn(new Date());
        fileView = nodeDao.createNewNode(fileView);
        nodesToDelete.add(fileView.getId());

        recordSet = new Node();
        recordSet.setName("recordSet");
        recordSet.setNodeType(EntityType.recordset);
        recordSet.setCreatedByPrincipalId(userId);
        recordSet.setCreatedOn(new Date());
        recordSet.setModifiedByPrincipalId(userId);
        recordSet.setModifiedOn(new Date());
        recordSet = nodeDao.createNewNode(recordSet);
        nodesToDelete.add(recordSet.getId());
    }

    @AfterEach
    public void afterEach() throws Exception {
        Collections.reverse(nodesToDelete);
        nodesToDelete.forEach(id -> nodeDao.delete(id));

        if (userId != null) {
            userGroupDAO.delete(userId.toString());
        }
    }


    @Test
    public void testCRUDFileBased() {
        FileBasedMetadataTask toCreate = new FileBasedMetadataTask()
                .setProjectId(project1.getId())
                .setDataType("fastq")
                .setInstructions("these are the instructions")
                .setFileViewId(fileView.getId())
                .setUploadFolderId(project1.getId());

        // Create
        FileBasedMetadataTask created = (FileBasedMetadataTask) dao.createMetadataTask(userId, toCreate);
        assertNotNull(created.getTaskId());
        assertEquals(project1.getId(), created.getProjectId());
        assertEquals("fastq", created.getDataType());
        assertEquals("these are the instructions", created.getInstructions());
        assertEquals(fileView.getId(), created.getFileViewId());
        assertEquals(project1.getId(), created.getUploadFolderId());
        assertEquals(userId.toString(), created.getCreatedBy());
        assertNotNull(created.getCreatedOn());
        assertEquals(userId.toString(), created.getModifiedBy());
        assertNotNull(created.getModifiedOn());
        assertNotNull(created.getEtag());

        // Read
        Optional<MetadataTask> fetched = dao.getMetadataTask(created.getTaskId());
        assertTrue(fetched.isPresent());
        assertEquals(created, fetched.get());

        // Update
        String newInstructions = "new instructions";
        created.setInstructions(newInstructions);
        FileBasedMetadataTask updated = (FileBasedMetadataTask) dao.updateMetadataTask(userId, created);
        assertEquals(newInstructions, updated.getInstructions());
        assertNotEquals(created.getEtag(), updated.getEtag());

        // Delete
        dao.deleteMetadataTask(created.getTaskId());
        assertTrue(dao.getMetadataTask(created.getTaskId()).isEmpty());
    }

    @Test
    public void testCRUDRecordBased() {
        RecordBasedMetadataTask toCreate = new RecordBasedMetadataTask()
                .setProjectId(project1.getId())
                .setDataType("fastq")
                .setInstructions("these are the instructions")
                .setRecordSetId(recordSet.getId());

        // Create
        RecordBasedMetadataTask created = (RecordBasedMetadataTask) dao.createMetadataTask(userId, toCreate);
        assertNotNull(created.getTaskId());
        assertEquals(project1.getId(), created.getProjectId());
        assertEquals("fastq", created.getDataType());
        assertEquals("these are the instructions", created.getInstructions());
        assertEquals(recordSet.getId(), created.getRecordSetId());
        assertEquals(userId.toString(), created.getCreatedBy());
        assertNotNull(created.getCreatedOn());
        assertEquals(userId.toString(), created.getModifiedBy());
        assertNotNull(created.getModifiedOn());
        assertNotNull(created.getEtag());

        // Read
        Optional<MetadataTask> fetched = dao.getMetadataTask(created.getTaskId());
        assertTrue(fetched.isPresent());
        assertEquals(created, fetched.get());

        // Update
        String newInstructions = "new instructions";
        created.setInstructions(newInstructions);
        RecordBasedMetadataTask updated = (RecordBasedMetadataTask) dao.updateMetadataTask(userId, created);
        assertEquals(newInstructions, updated.getInstructions());
        assertNotEquals(created.getEtag(), updated.getEtag());

        // Delete
        dao.deleteMetadataTask(created.getTaskId());
        assertTrue(dao.getMetadataTask(created.getTaskId()).isEmpty());
    }

    @Test
    public void testNoDuplicateDataTypeWithinProject() {
        RecordBasedMetadataTask fastqDataType1 = new RecordBasedMetadataTask()
                .setProjectId(project1.getId())
                .setDataType("fastq")
                .setRecordSetId(recordSet.getId());

        FileBasedMetadataTask fastqDataType2 = new FileBasedMetadataTask()
                .setProjectId(project1.getId())
                .setDataType("fastq")
                .setUploadFolderId(project1.getId())
                .setFileViewId(fileView.getId());

        // Create the first one
        MetadataTask created1 = dao.createMetadataTask(userId, fastqDataType1);
        assertNotNull(created1);

        // call under test
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> dao.createMetadataTask(userId, fastqDataType2));
        assertTrue(e.getMessage().contains("A metadata task with the specified data type already exists in this project."));

        // Verify that we can create the same data type in a different project
        fastqDataType2.setProjectId(project2.getId());
        MetadataTask created2 = dao.createMetadataTask(userId, fastqDataType2);
        assertNotNull(created2);

        // Cleanup
        dao.deleteMetadataTask(created1.getTaskId());
        dao.deleteMetadataTask(created2.getTaskId());
    }


    @Test
    public void testGetListForProject() {
        RecordBasedMetadataTask taskInProject1 = new RecordBasedMetadataTask()
                .setProjectId(project1.getId())
                .setDataType("fastq")
                .setRecordSetId(recordSet.getId());

        RecordBasedMetadataTask anotherTaskInProject1 = new RecordBasedMetadataTask()
                .setProjectId(project1.getId())
                .setDataType("rnaseq")
                .setRecordSetId(recordSet.getId());

        FileBasedMetadataTask taskInProject2 = new FileBasedMetadataTask()
                .setProjectId(project2.getId())
                .setDataType("fastq")
                .setUploadFolderId(project2.getId())
                .setFileViewId(fileView.getId());

        // Create
        MetadataTask projectOneTask1 = dao.createMetadataTask(userId, taskInProject1);
        MetadataTask projectOneTask2 = dao.createMetadataTask(userId, anotherTaskInProject1);
        MetadataTask projectTwoTask = dao.createMetadataTask(userId, taskInProject2);

        // call under test - get all tasks for project 1, ensure project 2 task is not included
        List<MetadataTask> tasksForProject1 = dao.getMetadataTasks(KeyFactory.stringToKey(project1.getId()), 10, 0);
        assertEquals(2, tasksForProject1.size());
        assertTrue(tasksForProject1.contains(projectOneTask1));
        assertTrue(tasksForProject1.contains(projectOneTask2));

        // Test limit and offset
        List<MetadataTask> tasks = dao.getMetadataTasks(KeyFactory.stringToKey(project1.getId()), 1, 0);
        assertEquals(1, tasks.size());
        assertEquals(projectOneTask1, tasks.get(0));

        tasks = dao.getMetadataTasks(KeyFactory.stringToKey(project1.getId()), 1, 1);
        assertEquals(1, tasks.size());
        assertEquals(projectOneTask2, tasks.get(0));


        // Cleanup
        dao.deleteMetadataTask(projectOneTask1.getTaskId());
        dao.deleteMetadataTask(projectOneTask2.getTaskId());
        dao.deleteMetadataTask(projectTwoTask.getTaskId());
    }


    @Test
    public void testConflictingUpdate() {
        RecordBasedMetadataTask toCreate = new RecordBasedMetadataTask()
                .setProjectId(project1.getId())
                .setDataType("fastq")
                .setInstructions("these are the instructions")
                .setRecordSetId(recordSet.getId());

        RecordBasedMetadataTask created = (RecordBasedMetadataTask) dao.createMetadataTask(userId, toCreate);
        assertNotNull(created.getTaskId());

        // Update, but scramble the etag
        String newInstructions = "new instructions";
        String wrongEtag = created.getEtag() + "xxx";
        created.setEtag(wrongEtag);
        created.setInstructions(newInstructions);

        assertThrows(ConflictingUpdateException.class, () -> dao.updateMetadataTask(userId, created));
    }
}