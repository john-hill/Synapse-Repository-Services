package org.sagebionetworks.repo.model.dbo.dao.dataaccess;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.repo.model.dataaccess.Submission;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.sagebionetworks.repo.model.dbo.dao.dataaccess.SubmissionUtils.writeSerializedField;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = { "classpath:jdomodels-test-context.xml" })
public class DBOSubmissionTest {


    @Test
    public void testSubmissionCreateDatabaseObjectFromBackupForNullVersion() {
        Submission submission = new Submission();
        submission.setAccessRequirementVersion(1L);

        DBOSubmission dbo = new DBOSubmission();
        dbo.setId(1L);
        dbo.setAccessRequirementId(11L);
        dbo.setSubmissionSerialized(writeSerializedField(submission));

        // call under test
        DBOSubmission updatedDBO = dbo.getTranslator().createDatabaseObjectFromBackup(dbo);

        assertEquals(submission.getAccessRequirementVersion(), updatedDBO.getAccessRequirementVersion());
    }

    @Test
    public void testSubmissionCreateDatabaseObjectFromBackupForNonNullVersion() {
        DBOSubmission dbo = new DBOSubmission();
        dbo.setId(1L);
        dbo.setAccessRequirementId(11L);
        dbo.setAccessRequirementVersion(1L);

        // call under test
        DBOSubmission updatedDBO = dbo.getTranslator().createDatabaseObjectFromBackup(dbo);

        assertEquals(1L, updatedDBO.getAccessRequirementVersion());
    }
}
