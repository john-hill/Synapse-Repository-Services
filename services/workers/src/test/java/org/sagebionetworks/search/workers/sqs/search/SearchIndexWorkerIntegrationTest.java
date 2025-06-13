package org.sagebionetworks.search.workers.sqs.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.repo.manager.EntityManager;
import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.manager.search.oss.SearchManager;
import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.Project;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.util.Pair;
import org.sagebionetworks.util.TimeUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = { "classpath:test-context.xml" })
public class SearchIndexWorkerIntegrationTest {
    private static final long MAX_WAIT = 2 * 60*1000; // 2 minutes
    private static final long CHECK_TIME = 2000;

    private UserInfo adminUser;

    @Autowired
    private EntityManager entityManager;
    @Autowired
    private UserManager userManager;
    @Autowired
    private SearchManager searchManager;

    @BeforeEach
    public void before() {
        adminUser = userManager.getUserInfo(AuthorizationConstants.BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId());
    }

    @Test
    public void testSearchIndexWorker() throws Exception {
        Project project = new Project();
        project.setName("OpenSearch.Project" + UUID.randomUUID());

        //creating entity, trigger create message. SearchIndexWorker will pick up the message and create a searchable document.
        String id = entityManager.createEntity(adminUser, project, null);
        project = entityManager.getEntity(adminUser, id, Project.class);
        assertNotNull(project);

        //call under test
        waitForQuery(id, project.getEtag());

    }

    /**
     * @param id
     * @throws Exception
     */
    public void waitForQuery(String id, String etag) throws Exception {
        TimeUtils.waitFor(MAX_WAIT, CHECK_TIME, () -> {
            System.out.println("Waiting for Get request to get the document.");
            return Pair.create(searchManager.doesDocumentExist(id, etag), null);
        });
    }
}
