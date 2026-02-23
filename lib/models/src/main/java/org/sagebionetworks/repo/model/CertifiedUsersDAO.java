package org.sagebionetworks.repo.model;

import org.sagebionetworks.repo.web.NotFoundException;

import java.util.Set;

public interface CertifiedUsersDAO {

    void addCertifiedUser(Long userId) throws DatastoreException, NotFoundException;

    void removeCertifiedUser(Long userId) throws DatastoreException, NotFoundException;

    boolean areCertifiedUsers(Set<String> userIds) throws DatastoreException, NotFoundException;

    boolean isCertifiedUser(String userId) throws DatastoreException, NotFoundException;

}
