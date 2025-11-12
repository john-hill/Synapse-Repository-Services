package org.sagebionetworks.repo.manager.file.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.repo.manager.file.FileHandleAssociationManager;
import org.sagebionetworks.repo.model.IdRange;
import org.sagebionetworks.repo.model.dbo.schema.EntitySchemaValidationResultDao;
import org.sagebionetworks.repo.model.dbo.schema.RecordSetValidationResult;
import org.sagebionetworks.repo.model.file.FileHandleAssociateType;
import org.sagebionetworks.repo.model.schema.ValidationSummaryStatistics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = { "classpath:test-context.xml" })
public class RecordSetValidationFileScannerIntegrationTest {
	
	@Autowired
	private EntitySchemaValidationResultDao validationResultDao;
	
	@Autowired
	private FileHandleAssociationManager manager;
	
	private FileHandleAssociateType associationType = FileHandleAssociateType.RecordSetValidationDetails;
	
	@BeforeEach
	public void before() {
		validationResultDao.truncateAll();
	}
	
	@AfterEach
	public void after() {
		validationResultDao.truncateAll();
	}	
	
	@Test
	public void testScanner() {
		
		validationResultDao.setRecordSetValidationResult(123L, 3L, new RecordSetValidationResult(new ValidationSummaryStatistics(), "123"));
		validationResultDao.setRecordSetValidationResult(456L, 2L, new RecordSetValidationResult(new ValidationSummaryStatistics(), null));
		validationResultDao.setRecordSetValidationResult(456L, 3L, new RecordSetValidationResult(new ValidationSummaryStatistics(), "456"));
		
		
		IdRange range = manager.getIdRange(associationType);
		
		// Call under test
		List<ScannedFileHandleAssociation> result = StreamSupport.stream(manager.scanRange(associationType, range).spliterator(), false).collect(Collectors.toList());
		
		assertEquals(2, result.size());
		assertEquals(Collections.singleton(123L), result.get(0).getFileHandleIds());
		assertEquals(Collections.singleton(456L), result.get(1).getFileHandleIds());
		
	}

}
