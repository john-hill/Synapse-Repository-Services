package org.sagebionetworks.evaluation.dbo.grid;

import static org.junit.Assert.assertNotNull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.AuthorizationConstants.BOOTSTRAP_PRINCIPAL;
import org.sagebionetworks.repo.model.dbo.grid.GridDao;
import org.sagebionetworks.repo.model.grid.GridSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = { "classpath:jdomodels-test-context.xml" })
public class GridDaoImplTest {

	private Long adminUserId = BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId();
	private UserInfo admin;

	@Autowired
	private GridDao dao;

	@BeforeEach
	public void before() {
		admin = new UserInfo(true, adminUserId);
	}

	@Test
	public void testCreateGridSession() {

		// call under test
		GridSession session = dao.createGridSession(adminUserId);
		System.out.println(session);
		assertNotNull(session);
	}
}
