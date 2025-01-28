package org.sagebionetworks.repo.model.dbo.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.model.dbo.migration.MigratableTableTranslation;
import org.sagebionetworks.repo.model.dbo.persistence.DBOQuizResponse;
import org.sagebionetworks.repo.model.jdo.JDOSecondaryPropertyUtils;
import org.sagebionetworks.repo.model.quiz.PassingRecord;

@ExtendWith(MockitoExtension.class)
public class DBOQuizResponseMigrationTest {
	
	private DBOQuizResponse backup;
	
	private MigratableTableTranslation<DBOQuizResponse, DBOQuizResponse> migrationTranslator = new DBOQuizResponse().getTranslator();
	
	@BeforeEach
	public void before() {
		backup = new DBOQuizResponse();
		backup.setId(123L);
	}
		
	@Test
	public void testRoundTripWithEmptyPassingRecordCreatedOn() {
		
		Date passedOn = new Date();
		
		backup.setPassingJson(JDOSecondaryPropertyUtils.createJSONFromObject(new PassingRecord()
			.setResponseId(backup.getId())
			.setRevoked(false)
			.setCertified(false)
			.setPassedOn(passedOn)
			.setPassed(false)
		));		

		DBOQuizResponse expected = migrationTranslator.createBackupFromDatabaseObject(backup);
		
		expected.setPassingJson(JDOSecondaryPropertyUtils.createJSONFromObject(new PassingRecord()
			.setResponseId(backup.getId())
			.setRevoked(false)
			.setCertified(false)
			.setPassedOn(passedOn)
			.setPassed(false)
			.setCreatedOn(passedOn)
		));
		
		DBOQuizResponse result = migrationTranslator.createDatabaseObjectFromBackup(backup);
		
		assertEquals(expected, result);
	}
	
	@Test
	public void testRoundTripWithExistingPassingRecordCreatedOn() {
		
		Date createdOn = new Date();
		
		backup.setPassingJson(JDOSecondaryPropertyUtils.createJSONFromObject(new PassingRecord()
			.setResponseId(backup.getId())
			.setRevoked(false)
			.setCertified(false)
			.setCreatedOn(createdOn)
			.setPassed(false)
		));
		
		DBOQuizResponse expected = migrationTranslator.createBackupFromDatabaseObject(backup);
				
		DBOQuizResponse result = migrationTranslator.createDatabaseObjectFromBackup(backup);
		
		assertEquals(expected, result);
	}

}
