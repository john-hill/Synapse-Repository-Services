package org.sagebionetworks.repo.manager.drs;

import org.sagebionetworks.StackConfiguration;
import org.sagebionetworks.repo.model.drs.OrganizationInformation;
import org.sagebionetworks.repo.model.drs.PackageInformation;
import org.sagebionetworks.repo.model.drs.ServiceInformation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;

@Service
public class DRSManagerImpl implements DRSManager {
    public static final String REVERSE_DOMAIN_NOTATION = "org.sagebase.prod.repo-prod";
    public static final String SERVICE_NAME = "Sage Bionetworks Synapse DRS API";
    public static final String CONTACT_URL = "https://sagebionetworks.jira.com/servicedesk/customer/portal/9";
    public static final String DOCUMENTATION_URL = "https://docs.synapse.org";
    public static final String DRS_URL = "https://repo-prod.prod.sagebase.org";
    public static final String DRS_GROUP = "org.ga4gh";
    public static final String DRS_ARTIFACT = "drs";
    public static final String DRS_VERSION = "1.2.0";
    public static final String ORGANIZATION_NAME = "Sage Bionetworks";
    public static final String ORGANIZATION_URL = "https://www.sagebionetworks.org";
    public static final String DESCRIPTION = "This service provides implementation of DRS specification for " +
            "accessing FileEntities and Datasets within Synapse.";
    private static final LocalDate localDate = LocalDate.of(2022, 8, 01);
    public static final Date CREATED_AT = Date.from(localDate.atStartOfDay(ZoneOffset.UTC).toInstant());
    public static final Date UPDATED_AT = Date.from(localDate.atStartOfDay(ZoneOffset.UTC).toInstant());

    private StackConfiguration stackConfiguration;

    @Autowired
    public DRSManagerImpl(StackConfiguration stackConfiguration) {
        super();
        this.stackConfiguration = stackConfiguration;
    }

    @Override
    public ServiceInformation getServiceInformation() {
        ServiceInformation result = new ServiceInformation();
        result.setId(REVERSE_DOMAIN_NOTATION);
        result.setName(SERVICE_NAME);
        PackageInformation drsPackageInformation = new PackageInformation();
        drsPackageInformation.setGroup(DRS_GROUP);
        drsPackageInformation.setArtifact(DRS_ARTIFACT);
        drsPackageInformation.setVersion(DRS_VERSION);
        result.setType(drsPackageInformation);
        result.setDescription(DESCRIPTION);
        OrganizationInformation organization = new OrganizationInformation();
        organization.setName(ORGANIZATION_NAME);
        organization.setUrl(ORGANIZATION_URL);
        result.setOrganization(organization);
        result.setContactUrl(CONTACT_URL);
        result.setDocumentationUrl(DOCUMENTATION_URL);
        result.setCreatedAt(CREATED_AT);
        result.setUpdatedAt(UPDATED_AT);
        result.setEnvironment(stackConfiguration.getStack());
        result.setVersion(stackConfiguration.getStackInstance());
        result.setUrl(DRS_URL);
        return result;
    }
}
