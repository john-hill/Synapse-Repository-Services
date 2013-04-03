package org.sagebionetworks.repo.model.dbo.dao;

import java.util.LinkedList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.UserGroup;
import org.sagebionetworks.repo.model.UserGroupDAO;
import org.sagebionetworks.repo.model.dao.FileHandleDao;
import org.sagebionetworks.repo.model.dbo.DBOBasicDao;
import org.sagebionetworks.repo.model.dbo.persistence.DBOFileHandle;
import org.sagebionetworks.repo.model.dbo.persistence.DBOFileHandle.MetadataType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = { "classpath:jdomodels-test-context.xml" })
public class BatchTest {
	
	@Autowired
	FileHandleDao fileHandleDao;
	
	@Autowired
	private DBOBasicDao basicDao;
	
	@Autowired
	private UserGroupDAO userGroupDAO;
	
	private List<String> toDelete;
	UserGroup user;
	
	
	@Before
	public void before(){
		user = userGroupDAO.findGroup(AuthorizationConstants.BOOTSTRAP_USER_GROUP_NAME, false);
	}
	
	@Test
	public void test(){
		// Create a batch 
		long count = 10000;
		List<DBOFileHandle> batch = createBatch((int)count, 0);
		// Insert one at a time
		long start = System.currentTimeMillis();
		for(DBOFileHandle s3Handle: batch){
			basicDao.createNew(s3Handle);
		}
//		basicDao.createBatch(batch);
		long elapse = System.currentTimeMillis()-start;
		double rateRowsPerMS = ((double)count)/((double)elapse);
		System.out.println("Inserted "+count+" rows in "+elapse+" MS at a rate of "+rateRowsPerMS+" rows/MS");
	}
	
	private List<DBOFileHandle> createBatch(int size, int startNumber){
		List<DBOFileHandle> results = new LinkedList<DBOFileHandle>();
		for(int i=startNumber; i<size+startNumber; i++){
			results.add(createHandle(i));
		}
		return results;
	}

	public DBOFileHandle createHandle(int i) {
		DBOFileHandle handle = new DBOFileHandle();
		handle.setId(new Long(i));
		handle.setBucketName("bucket"+i);
		handle.setKey("key"+i);
		handle.setCreatedBy(Long.parseLong(user.getId()));
		handle.setCreatedOn(null);
		handle.setContentType("contentType"+i);
		handle.setEtag("etag"+i);
		handle.setName("foo.bar"+i);
		handle.setMetadataType(MetadataType.S3);
		handle.setContentSize((long)i);
		handle.setContentMD5("md5"+i);
		if(i  > 0){
			handle.setPreviewId((long) (i-1));
		}
		return handle;
	}

}
