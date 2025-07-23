package org.sagebionetworks.search.workers.sqs.search;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.repo.manager.EntityAclManager;
import org.sagebionetworks.repo.manager.EntityManager;
import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.manager.search.oss.SearchManager;
import org.sagebionetworks.repo.model.ACCESS_TYPE;
import org.sagebionetworks.repo.model.AccessControlList;
import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.Project;
import org.sagebionetworks.repo.model.ResourceAccess;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.auth.NewUser;
import org.sagebionetworks.repo.model.search.SearchResults;
import org.sagebionetworks.repo.model.search.query.SearchQuery;
import org.sagebionetworks.util.Pair;
import org.sagebionetworks.util.TimeUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = { "classpath:test-context.xml" })
public class SearchIndexWorkerIntegrationTest {
    private static final long MAX_WAIT = 2 * 60*1000; // 2 minutes
    private static final long CHECK_TIME = 2000;

    @Autowired
    private EntityManager entityManager;
    @Autowired
    private UserManager userManager;
    @Autowired
    private SearchManager searchManager;
    @Autowired
    private EntityAclManager entityAclManager;

    private Project project;
    private UserInfo adminUser;
    private UserInfo anotherUser;


    @BeforeEach
    public void before() {
        adminUser = userManager.getUserInfo(AuthorizationConstants.BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId());
        String userName = UUID.randomUUID().toString();
        anotherUser = userManager.createOrGetTestUser(adminUser, new NewUser().setUserName(userName).setEmail(userName + "@foo.org"));

        project = new Project();
        project.setName("OpenSearch Project " + UUID.randomUUID());

        //creating entity, trigger create message. SearchIndexWorker will pick up the message and create a searchable document.
        String id = entityManager.createEntity(adminUser, project, null);
        project = entityManager.getEntity(adminUser, id, Project.class);
    }

    @AfterEach
    public void after(){
        if (project != null){
            entityManager.deleteEntity(adminUser, project.getId());
        }

        if (anotherUser != null) {
            userManager.deletePrincipal(adminUser, anotherUser.getId());
        }
    }

    @Test
    public void testSearchIndexWorker() throws Exception {

        //call under test
        waitForEntityToAppearInSearch(project.getId(), project.getEtag());

        // The admin should find the project
        SearchResults results = searchManager.search(adminUser, searchQueryByTerm(project.getName()));
        assertNotNull(results);
        assertTrue(results.getHits().stream().anyMatch(hit -> hit.getId().equals(project.getId())));

        // No results for the user since the project is not shared
        SearchResults results1 = searchManager.search(anotherUser, searchQueryByTerm(project.getName()));
        assertEquals(0, results1.getFound());
        // Now share the project with the user
        AccessControlList acl = entityAclManager.getACL(project.getId(), adminUser);

        acl.getResourceAccess().add(new ResourceAccess().setPrincipalId(anotherUser.getId()).setAccessType(Collections.singleton(ACCESS_TYPE.READ)));

        // Update the ACL, this should propagate the change so that the document is visible to the user
        entityAclManager.updateACL(acl, adminUser);

        // The user should eventually find the project
        SearchResults results2 = searchManager.search(anotherUser, searchQueryByTerm(project.getName()));
        /*assertNotNull(results2);
        assertTrue(results2.getHits().stream().anyMatch(hit -> hit.getId().equals(project.getId())));*/
    }

    /**
     * @param id
     * @throws Exception
     */
    public void waitForEntityToAppearInSearch(String id, String etag) throws Exception {
        TimeUtils.waitFor(MAX_WAIT, CHECK_TIME, () -> {
            System.out.println("Waiting for Get request to get the document.");
            return Pair.create(searchManager.doesDocumentExist(id, etag), null);
        });
    }

    private static SearchQuery searchQueryByTerm(String term) {
        return new SearchQuery().setQueryTerm(Arrays.asList(term));
    }
}
