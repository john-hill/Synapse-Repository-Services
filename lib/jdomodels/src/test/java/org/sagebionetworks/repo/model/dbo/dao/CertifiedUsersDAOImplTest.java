package org.sagebionetworks.repo.model.dbo.dao;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.CertifiedUsersDAO;
import org.sagebionetworks.repo.model.UserGroup;
import org.sagebionetworks.repo.model.UserGroupDAO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = {"classpath:jdomodels-test-context.xml"})
public class CertifiedUsersDAOImplTest {

    @Autowired
    private UserGroupDAO userGroupDAO;

    @Autowired
    CertifiedUsersDAO certifiedUsersDAO;

    private List<String> groupsToDelete;

    private Long longUserId;


    @BeforeEach
    public void setUp() throws Exception {
        groupsToDelete = new ArrayList<String>();

        UserGroup group = new UserGroup();
        group.setIsIndividual(true);
        group.setRealmId(AuthorizationConstants.DEFAULT_REALM_ID);
        group.setCreationDate(new Date());
        longUserId = userGroupDAO.create(group);
        groupsToDelete.add(longUserId.toString());
    }

    @AfterEach
    public void tearDown() throws Exception {
        for (String toDelete : groupsToDelete) {
            userGroupDAO.delete(toDelete);
        }

    }

    @Test
    public void testCURDCertifiedUser() {
        //call under test
        Boolean isCertified =  certifiedUsersDAO.isCertifiedUser(longUserId.toString());
        assertFalse(isCertified);

        certifiedUsersDAO.addCertifiedUser(longUserId);
        assertTrue(certifiedUsersDAO.isCertifiedUser(longUserId.toString()));

        certifiedUsersDAO.removeCertifiedUser(longUserId);
        assertFalse(certifiedUsersDAO.isCertifiedUser(longUserId.toString()));
    }

    @Test
    public void testIsCertifiedWithWrongId() {
        //call under test
        Boolean isCertified =  certifiedUsersDAO.isCertifiedUser("-99");
        assertFalse(isCertified);

    }

    @Test
    public void testIsCertifiedWithNullId() {
        //call under test
        Boolean isCertified =  certifiedUsersDAO.isCertifiedUser(null);
        assertFalse(isCertified);

    }

    @Test
    public void testIsCertifiedWithEmptyString() {
        //call under test
        Boolean isCertified =  certifiedUsersDAO.isCertifiedUser("");
        assertFalse(isCertified);

    }

    @Test
    public void testAreCertifiedWithNull() {
        //call under test
        Boolean isCertified =  certifiedUsersDAO.areCertifiedUsers(null);
        assertFalse(isCertified);

    }

    @Test
    public void testAreCertifiedWithEmptySet() {
        //call under test
        Boolean isCertified =  certifiedUsersDAO.areCertifiedUsers(Collections.emptySet());
        assertFalse(isCertified);

    }

}
