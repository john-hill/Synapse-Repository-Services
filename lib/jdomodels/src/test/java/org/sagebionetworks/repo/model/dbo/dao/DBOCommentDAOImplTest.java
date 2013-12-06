package org.sagebionetworks.repo.model.dbo.dao;

import static junit.framework.Assert.assertNotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.CommentDAO;
import org.sagebionetworks.repo.model.MessageDAO;
import org.sagebionetworks.repo.model.ObjectType;
import org.sagebionetworks.repo.model.Principal;
import org.sagebionetworks.repo.model.PrincipalDAO;
import org.sagebionetworks.repo.model.dao.FileHandleDao;
import org.sagebionetworks.repo.model.file.S3FileHandle;
import org.sagebionetworks.repo.model.message.Comment;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = { "classpath:jdomodels-test-context.xml" })
public class DBOCommentDAOImplTest {
	
	@Autowired
	private CommentDAO commentDAO;

	@Autowired
	private MessageDAO messageDAO;
	
	@Autowired
	private PrincipalDAO userGroupDAO;
	
	@Autowired
	private FileHandleDao fileDAO;
	
	private String fileHandleId;	
	private Principal maliciousUser;
	
	private List<String> cleanup;
	
	@Before
	public void setup() throws Exception {
		cleanup = new ArrayList<String>();
		
		maliciousUser = new Principal();
		maliciousUser.setEmail(UUID.randomUUID().toString()+"@test.com");
		maliciousUser.setPrincipalName(UUID.randomUUID().toString());
		maliciousUser.setIsIndividual(true);
		maliciousUser.setId(userGroupDAO.create(maliciousUser));
		
		// We need a file handle to satisfy a foreign key constraint
		// But it doesn't need to point to an actual file
		// Also, it doesn't matter who the handle is tied to
		S3FileHandle handle = TestUtils.createS3FileHandle(maliciousUser.getId());
		handle = fileDAO.createFile(handle);
		fileHandleId = handle.getId();
	}
	
	@After
	public void cleanup() throws Exception {
		for (String id : cleanup) {
			messageDAO.deleteMessage(id);
		}
		fileDAO.delete(fileHandleId);
		if(maliciousUser != null){
			try {
				userGroupDAO.delete(maliciousUser.getId());
			} catch (Exception e) {} 
		}
	}
	
	@Test
	public void testCreate() throws Exception {
		Comment dto = new Comment();
		// Note: ID is auto generated
		dto.setCreatedBy(maliciousUser.getId());
		dto.setFileHandleId(fileHandleId);
		// Note: CreatedOn is set by the DAO
		dto.setTargetId("1337");
		dto.setTargetType(ObjectType.ENTITY);
		
		dto = commentDAO.createComment(dto);
		assertNotNull(dto.getId());
		cleanup.add(dto.getId());
		assertNotNull(dto.getCreatedOn());
	}
}
