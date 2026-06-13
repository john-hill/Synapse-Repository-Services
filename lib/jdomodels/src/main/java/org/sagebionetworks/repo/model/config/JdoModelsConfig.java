package org.sagebionetworks.repo.model.config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.sagebionetworks.StackConfiguration;
import org.sagebionetworks.evaluation.dbo.AnnotationsBlobDBO;
import org.sagebionetworks.evaluation.dbo.AnnotationsOwnerDBO;
import org.sagebionetworks.evaluation.dbo.DoubleAnnotationDBO;
import org.sagebionetworks.evaluation.dbo.EvaluationDBO;
import org.sagebionetworks.evaluation.dbo.EvaluationRoundDBO;
import org.sagebionetworks.evaluation.dbo.EvaluationSubmissionsDBO;
import org.sagebionetworks.evaluation.dbo.LongAnnotationDBO;
import org.sagebionetworks.evaluation.dbo.StringAnnotationDBO;
import org.sagebionetworks.evaluation.dbo.SubmissionContributorDBO;
import org.sagebionetworks.evaluation.dbo.SubmissionDBO;
import org.sagebionetworks.evaluation.dbo.SubmissionFileHandleDBO;
import org.sagebionetworks.evaluation.dbo.SubmissionStatusDBO;
import org.sagebionetworks.repo.model.dbo.DBOBasicDao;
import org.sagebionetworks.repo.model.dbo.DBOBasicDaoImpl;
import org.sagebionetworks.repo.model.dbo.DDLUtils;
import org.sagebionetworks.repo.model.dbo.DDLUtilsImpl;
import org.sagebionetworks.repo.model.dbo.agent.DBOAgentRegistration;
import org.sagebionetworks.repo.model.dbo.agent.DBOAgentSession;
import org.sagebionetworks.repo.model.dbo.agent.DBOAgentTrace;
import org.sagebionetworks.repo.model.dbo.asynch.DBOAsynchJobStatus;
import org.sagebionetworks.repo.model.dbo.auth.DBOTermsOfServiceAgreement;
import org.sagebionetworks.repo.model.dbo.auth.DBOTermsOfServiceLatestVersion;
import org.sagebionetworks.repo.model.dbo.auth.DBOTermsOfServiceRequirements;
import org.sagebionetworks.repo.model.dbo.auth.DBOUserStatus;
import org.sagebionetworks.repo.model.dbo.auth.DBOUserTwoFaStatus;
import org.sagebionetworks.repo.model.dbo.curation.DBOCurationTask;
import org.sagebionetworks.repo.model.dbo.dao.dataaccess.DBOAccessRequirementProject;
import org.sagebionetworks.repo.model.dbo.dao.dataaccess.DBODataAccessNotification;
import org.sagebionetworks.repo.model.dbo.dao.dataaccess.DBORequest;
import org.sagebionetworks.repo.model.dbo.dao.dataaccess.DBOResearchProject;
import org.sagebionetworks.repo.model.dbo.dao.dataaccess.DBOSubmission;
import org.sagebionetworks.repo.model.dbo.dao.dataaccess.DBOSubmissionAccessorChange;
import org.sagebionetworks.repo.model.dbo.dao.dataaccess.DBOSubmissionStatus;
import org.sagebionetworks.repo.model.dbo.dao.dataaccess.DBOSubmissionSubmitter;
import org.sagebionetworks.repo.model.dbo.dao.files.DBOFilesScannerStatus;
import org.sagebionetworks.repo.model.dbo.dao.table.DBOTableSnapshot;
import org.sagebionetworks.repo.model.dbo.dao.table.DBOViewScope;
import org.sagebionetworks.repo.model.dbo.dao.table.DBOViewType;
import org.sagebionetworks.repo.model.dbo.feature.DBOFeatureStatus;
import org.sagebionetworks.repo.model.dbo.file.DBOMultipartUpload;
import org.sagebionetworks.repo.model.dbo.file.DBOMultipartUploadComposerPartState;
import org.sagebionetworks.repo.model.dbo.file.DBOMultipartUploadPartState;
import org.sagebionetworks.repo.model.dbo.form.DBOFormData;
import org.sagebionetworks.repo.model.dbo.form.DBOFormGroup;
import org.sagebionetworks.repo.model.dbo.grid.DBOGridConnection;
import org.sagebionetworks.repo.model.dbo.grid.DBOGridPatch;
import org.sagebionetworks.repo.model.dbo.grid.DBOGridReplica;
import org.sagebionetworks.repo.model.dbo.grid.DBOGridSession;
import org.sagebionetworks.repo.model.dbo.grid.DBOGridSnapshot;
import org.sagebionetworks.repo.model.dbo.limits.DBOProjectStorageData;
import org.sagebionetworks.repo.model.dbo.limits.DBOProjectStorageLimit;
import org.sagebionetworks.repo.model.dbo.loginlockout.DBOUnsuccessfulLoginLockout;
import org.sagebionetworks.repo.model.dbo.migration.MigratableTableDAO;
import org.sagebionetworks.repo.model.dbo.migration.MigratableTableDAOImpl;
import org.sagebionetworks.repo.model.dbo.otp.DBOOtpRecoveryCode;
import org.sagebionetworks.repo.model.dbo.otp.DBOOtpSecret;
import org.sagebionetworks.repo.model.dbo.persistence.DBOAccessApproval;
import org.sagebionetworks.repo.model.dbo.persistence.DBOAccessControlList;
import org.sagebionetworks.repo.model.dbo.persistence.DBOAccessRequirement;
import org.sagebionetworks.repo.model.dbo.persistence.DBOAccessRequirementRevision;
import org.sagebionetworks.repo.model.dbo.persistence.DBOActivity;
import org.sagebionetworks.repo.model.dbo.persistence.DBOAuthenticatedOn;
import org.sagebionetworks.repo.model.dbo.persistence.DBOAuthorizationCode;
import org.sagebionetworks.repo.model.dbo.persistence.DBOAuthorizationConsent;
import org.sagebionetworks.repo.model.dbo.persistence.DBOCertifiedUser;
import org.sagebionetworks.repo.model.dbo.persistence.DBOChallenge;
import org.sagebionetworks.repo.model.dbo.persistence.DBOChallengeTeam;
import org.sagebionetworks.repo.model.dbo.persistence.DBOChange;
import org.sagebionetworks.repo.model.dbo.persistence.DBOComment;
import org.sagebionetworks.repo.model.dbo.persistence.DBOCredential;
import org.sagebionetworks.repo.model.dbo.persistence.DBODataType;
import org.sagebionetworks.repo.model.dbo.persistence.DBODockerCommit;
import org.sagebionetworks.repo.model.dbo.persistence.DBODockerManagedRepositoryName;
import org.sagebionetworks.repo.model.dbo.persistence.DBODoi;
import org.sagebionetworks.repo.model.dbo.persistence.DBOFavorite;
import org.sagebionetworks.repo.model.dbo.persistence.DBOFileHandle;
import org.sagebionetworks.repo.model.dbo.persistence.DBOGroupMembers;
import org.sagebionetworks.repo.model.dbo.persistence.DBOMembershipInvitation;
import org.sagebionetworks.repo.model.dbo.persistence.DBOMembershipRequest;
import org.sagebionetworks.repo.model.dbo.persistence.DBOMessageContent;
import org.sagebionetworks.repo.model.dbo.persistence.DBOMessageRecipient;
import org.sagebionetworks.repo.model.dbo.persistence.DBOMessageStatus;
import org.sagebionetworks.repo.model.dbo.persistence.DBOMessageToUser;
import org.sagebionetworks.repo.model.dbo.persistence.DBONode;
import org.sagebionetworks.repo.model.dbo.persistence.DBOOAuthAccessToken;
import org.sagebionetworks.repo.model.dbo.persistence.DBOOAuthClient;
import org.sagebionetworks.repo.model.dbo.persistence.DBOOAuthRefreshToken;
import org.sagebionetworks.repo.model.dbo.persistence.DBOPersonalAccessToken;
import org.sagebionetworks.repo.model.dbo.persistence.DBOPrincipalPrefix;
import org.sagebionetworks.repo.model.dbo.persistence.DBOProcessedMessage;
import org.sagebionetworks.repo.model.dbo.persistence.DBOProjectSetting;
import org.sagebionetworks.repo.model.dbo.persistence.DBOProjectStat;
import org.sagebionetworks.repo.model.dbo.persistence.DBOQuizResponse;
import org.sagebionetworks.repo.model.dbo.persistence.DBORealm;
import org.sagebionetworks.repo.model.dbo.persistence.DBORealmIdentityProvider;
import org.sagebionetworks.repo.model.dbo.persistence.DBORealmPrincipal;
import org.sagebionetworks.repo.model.dbo.persistence.DBOResourceAccess;
import org.sagebionetworks.repo.model.dbo.persistence.DBOResourceAccessType;
import org.sagebionetworks.repo.model.dbo.persistence.DBORevision;
import org.sagebionetworks.repo.model.dbo.persistence.DBOSectorIdentifier;
import org.sagebionetworks.repo.model.dbo.persistence.DBOSentMessage;
import org.sagebionetworks.repo.model.dbo.persistence.DBOStackStatus;
import org.sagebionetworks.repo.model.dbo.persistence.DBOStorageLocation;
import org.sagebionetworks.repo.model.dbo.persistence.DBOSubjectAccessRequirement;
import org.sagebionetworks.repo.model.dbo.persistence.DBOTeam;
import org.sagebionetworks.repo.model.dbo.persistence.DBOUserGroup;
import org.sagebionetworks.repo.model.dbo.persistence.DBOUserProfile;
import org.sagebionetworks.repo.model.dbo.persistence.DBOVerificationState;
import org.sagebionetworks.repo.model.dbo.persistence.DBOVerificationSubmission;
import org.sagebionetworks.repo.model.dbo.persistence.DBOVerificationSubmissionFile;
import org.sagebionetworks.repo.model.dbo.persistence.discussion.DBODiscussionReply;
import org.sagebionetworks.repo.model.dbo.persistence.discussion.DBODiscussionSearchIndexRecord;
import org.sagebionetworks.repo.model.dbo.persistence.discussion.DBODiscussionThread;
import org.sagebionetworks.repo.model.dbo.persistence.discussion.DBODiscussionThreadEntityReference;
import org.sagebionetworks.repo.model.dbo.persistence.discussion.DBODiscussionThreadStats;
import org.sagebionetworks.repo.model.dbo.persistence.discussion.DBODiscussionThreadSubmissionReference;
import org.sagebionetworks.repo.model.dbo.persistence.discussion.DBODiscussionThreadView;
import org.sagebionetworks.repo.model.dbo.persistence.discussion.DBOForum;
import org.sagebionetworks.repo.model.dbo.persistence.subscription.DBOSubscription;
import org.sagebionetworks.repo.model.dbo.persistence.table.DBOBoundColumnOrdinal;
import org.sagebionetworks.repo.model.dbo.persistence.table.DBOBoundColumnOwner;
import org.sagebionetworks.repo.model.dbo.persistence.table.DBOColumnModel;
import org.sagebionetworks.repo.model.dbo.persistence.table.DBOMaterializedViewId;
import org.sagebionetworks.repo.model.dbo.persistence.table.DBOMaterializedViewSourceTable;
import org.sagebionetworks.repo.model.dbo.persistence.table.DBOTableIdSequence;
import org.sagebionetworks.repo.model.dbo.persistence.table.DBOTableRowChange;
import org.sagebionetworks.repo.model.dbo.persistence.table.DBOTableStatus;
import org.sagebionetworks.repo.model.dbo.persistence.table.DBOTableTransaction;
import org.sagebionetworks.repo.model.dbo.persistence.table.DBOTransactionToVersion;
import org.sagebionetworks.repo.model.dbo.portals.DBOPortal;
import org.sagebionetworks.repo.model.dbo.principal.DBONotificationEmail;
import org.sagebionetworks.repo.model.dbo.principal.DBOPrincipalAlias;
import org.sagebionetworks.repo.model.dbo.principal.DBOPrincipalOIDCBinding;
import org.sagebionetworks.repo.model.dbo.schema.DBODerivedAnnotations;
import org.sagebionetworks.repo.model.dbo.schema.DBOJsonSchema;
import org.sagebionetworks.repo.model.dbo.schema.DBOJsonSchemaBindObject;
import org.sagebionetworks.repo.model.dbo.schema.DBOJsonSchemaBlob;
import org.sagebionetworks.repo.model.dbo.schema.DBOJsonSchemaDependency;
import org.sagebionetworks.repo.model.dbo.schema.DBOJsonSchemaLatestVersion;
import org.sagebionetworks.repo.model.dbo.schema.DBOJsonSchemaVersion;
import org.sagebionetworks.repo.model.dbo.schema.DBOOrganization;
import org.sagebionetworks.repo.model.dbo.schema.DBORecordSetValidationStats;
import org.sagebionetworks.repo.model.dbo.schema.DBOSchemaValidationResults;
import org.sagebionetworks.repo.model.dbo.schema.DBOValidationJsonSchemaIndex;
import org.sagebionetworks.repo.model.dbo.search.DBOColumnAnalyzerOverride;
import org.sagebionetworks.repo.model.dbo.search.DBOSearchConfigBindObject;
import org.sagebionetworks.repo.model.dbo.search.DBOSearchConfiguration;
import org.sagebionetworks.repo.model.dbo.search.DBOSynonymSet;
import org.sagebionetworks.repo.model.dbo.search.DBOTextAnalyzer;
import org.sagebionetworks.repo.model.dbo.ses.DBOQuarantinedEmail;
import org.sagebionetworks.repo.model.dbo.ses.DBOSESNotification;
import org.sagebionetworks.repo.model.dbo.statistics.DBOStatisticsMonthlyProjectFiles;
import org.sagebionetworks.repo.model.dbo.statistics.DBOStatisticsMonthlyStatus;
import org.sagebionetworks.repo.model.dbo.throttle.DBOThrottleRule;
import org.sagebionetworks.repo.model.dbo.trash.DBOTrashedEntity;
import org.sagebionetworks.repo.model.dbo.webhook.DBOWebhook;
import org.sagebionetworks.repo.model.dbo.webhook.DBOWebhookAllowedDomain;
import org.sagebionetworks.repo.model.dbo.webhook.DBOWebhookVerification;
import org.sagebionetworks.repo.model.dbo.wikiV2.V2DBOWikiAttachmentReservation;
import org.sagebionetworks.repo.model.dbo.wikiV2.V2DBOWikiMarkdown;
import org.sagebionetworks.repo.model.dbo.wikiV2.V2DBOWikiOwner;
import org.sagebionetworks.repo.model.dbo.wikiV2.V2DBOWikiPage;
import org.sagebionetworks.repo.model.message.DBOBroadcastMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JDO Models configuration for DBO infrastructure beans.
 * Replaces dbo-beans.spb.xml with Java configuration.
 */
@Configuration
@Import(DatabaseInfrastructureConfiguration.class)
public class JdoModelsConfig {

    @Bean
    public DDLUtils ddlUtils() {
        return new DDLUtilsImpl();
    }

    @Bean
    public DBOBasicDao dboBasicDao() {
        DBOBasicDaoImpl dao = new DBOBasicDaoImpl();
        dao.setDatabaseObjectRegister(createDatabaseObjectRegister());
        dao.setFunctionMap(createFunctionMap());
        return dao;
    }

    /**
     * Creates the map of MySQL functions to be created/updated.
     * These are custom MySQL functions used by the application.
     */
    private Map<String, String> createFunctionMap() {
        Map<String, String> functionMap = new HashMap<>();
        functionMap.put("getEntityBenefactorId", "schema/functions/GetEntityBenefactorId.ddl.sql");
        functionMap.put("getEntityProjectId", "schema/functions/GetEntityProjectId.ddl.sql");
        functionMap.put("getEntityHierarchy", "schema/functions/GetEntityHierarchy.ddl.sql");
        return functionMap;
    }

    /**
     * Creates the list of database objects registered with DBOBasicDao.
     * Order matters for foreign key dependencies.
     * This list comes from dbo-beans.spb.xml databaseObjectRegister property.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private List createDatabaseObjectRegister() {
        // @formatter:off
        return List.of(
            new DBORealm(),
            new DBORealmIdentityProvider(),
            // DBOUserGroup must be declared before tables that have a foreign key to it
            new DBOUserGroup(),
            new DBORealmPrincipal(),
            new DBOPrincipalPrefix(),
            new DBOGroupMembers(),
            new DBOCertifiedUser(),
            new DBOCredential(),
            new DBOAuthenticatedOn(),
            new DBOPrincipalAlias(),
            new DBONotificationEmail(),
            new DBOActivity(),
            new DBOStorageLocation(),
            new DBOFileHandle(),
            new DBOMultipartUpload(),
            new DBOMultipartUploadPartState(),
            new DBOMultipartUploadComposerPartState(),
            // Messages
            new DBOMessageContent(),
            new DBOMessageToUser(),
            new DBOMessageRecipient(),
            new DBOMessageStatus(),
            new DBOComment(),
            new DBONode(),
            new DBORevision(),
            new DBODockerManagedRepositoryName(),
            new DBODockerCommit(),
            new DBOAccessControlList(),
            new DBOResourceAccess(),
            new DBOResourceAccessType(),
            new DBOUserProfile(),
            new DBOProjectSetting(),
            new DBOProjectStat(),
            new DBOAccessRequirement(),
            new DBOSubjectAccessRequirement(),
            new DBOAccessRequirementRevision(),
            new DBOAccessApproval(),
            new DBOStackStatus(),
            new DBOChange(),
            new DBOTrashedEntity(),
            new V2DBOWikiPage(),
            new V2DBOWikiAttachmentReservation(),
            new V2DBOWikiMarkdown(),
            new V2DBOWikiOwner(),
            new DBOFavorite(),
            new DBOPortal(),
            new DBODoi(),
            // Team-related beans
            new DBOTeam(),
            new DBOMembershipInvitation(),
            new DBOMembershipRequest(),
            // Evaluation beans
            new EvaluationDBO(),
            new EvaluationRoundDBO(),
            new EvaluationSubmissionsDBO(),
            new SubmissionDBO(),
            new SubmissionContributorDBO(),
            new SubmissionStatusDBO(),
            new SubmissionFileHandleDBO(),
            // Annotation beans
            new AnnotationsOwnerDBO(),
            new StringAnnotationDBO(),
            new LongAnnotationDBO(),
            new DoubleAnnotationDBO(),
            new AnnotationsBlobDBO(),
            // Challenge
            new DBOChallenge(),
            new DBOChallengeTeam(),
            // Table
            new DBOColumnModel(),
            new DBOBoundColumnOwner(),
            new DBOBoundColumnOrdinal(),
            new DBOTableTransaction(),
            new DBOTransactionToVersion(),
            new DBOTableIdSequence(),
            new DBOTableRowChange(),
            new DBOTableStatus(),
            // Job Status
            new DBOAsynchJobStatus(),
            // misc
            new DBOSentMessage(),
            new DBOProcessedMessage(),
            new DBOQuizResponse(),
            new DBOVerificationSubmission(),
            new DBOVerificationState(),
            new DBOVerificationSubmissionFile(),
            new DBOForum(),
            new DBODiscussionThread(),
            new DBODiscussionThreadStats(),
            new DBODiscussionThreadView(),
            new DBODiscussionThreadEntityReference(),
            new DBODiscussionReply(),
            new DBODiscussionSearchIndexRecord(),
            new DBOSubscription(),
            new DBOBroadcastMessage(),
            new DBOViewType(),
            new DBOViewScope(),
            new DBOTableSnapshot(),
            new DBOThrottleRule(),
            new DBOUnsuccessfulLoginLockout(),
            new DBOResearchProject(),
            new DBORequest(),
            new DBOSubmission(),
            new DBOSubmissionSubmitter(),
            new DBOSubmissionStatus(),
            new DBODataAccessNotification(),
            new DBOSubmissionAccessorChange(),
            new DBOAccessRequirementProject(),
            new DBODiscussionThreadSubmissionReference(),
            // Forms
            new DBOFormGroup(),
            new DBOFormData(),
            //  Schema
            new DBOOrganization(),
            new DBOJsonSchema(),
            new DBOJsonSchemaBlob(),
            new DBOJsonSchemaVersion(),
            new DBOJsonSchemaLatestVersion(),
            new DBOJsonSchemaDependency(),
            new DBOJsonSchemaBindObject(),
            new DBOSchemaValidationResults(),
            new DBOValidationJsonSchemaIndex(),
            new DBODerivedAnnotations(),
            // Search Configuration
            new DBOTextAnalyzer(),
            new DBOColumnAnalyzerOverride(),
            new DBOSynonymSet(),
            new DBOSearchConfiguration(),
            new DBOSearchConfigBindObject(),
            // Download
            new org.sagebionetworks.repo.model.dbo.file.download.DBODownloadList(),
            new org.sagebionetworks.repo.model.dbo.file.download.DBODownloadListItem(),
            new org.sagebionetworks.repo.model.dbo.file.download.DBODownloadOrder(),
            new org.sagebionetworks.repo.model.dbo.file.download.v2.DBODownloadList(),
            new org.sagebionetworks.repo.model.dbo.file.download.v2.DBODownloadListItem(),
            new DBODataType(),
            new DBOSectorIdentifier(),
            new DBOOAuthClient(),
            new DBOOAuthRefreshToken(),
            new DBOOAuthAccessToken(),
            new DBOPersonalAccessToken(),
            new DBOAuthorizationConsent(),
            new DBOAuthorizationCode(),
            // Statistics
            new DBOStatisticsMonthlyStatus(),
            new DBOStatisticsMonthlyProjectFiles(),
            // SES Notifications
            new DBOSESNotification(),
            // Email Quarantine
            new DBOQuarantinedEmail(),
            // Feature Status
            new DBOFeatureStatus(),
            // Files Scanner Status
            new DBOFilesScannerStatus(),
            // Materialized view tables
            new DBOMaterializedViewId(),
            new DBOMaterializedViewSourceTable(),
            new DBOPrincipalOIDCBinding(),
            // 2FA tables
            new DBOOtpSecret(),
            new DBOOtpRecoveryCode(),
            new DBOUserTwoFaStatus(),
            // Webhook table
            new DBOWebhook(),
            new DBOWebhookVerification(),
            new DBOWebhookAllowedDomain(),
            new DBOAgentRegistration(),
            new DBOAgentSession(),
            new DBOAgentTrace(),
            new DBOTermsOfServiceRequirements(),
            new DBOTermsOfServiceAgreement(),
            new DBOTermsOfServiceLatestVersion(),
            new DBOProjectStorageData(),
            new DBOProjectStorageLimit(),
            // Grid
            new DBOGridSession(),
            new DBOGridReplica(),
            new DBOGridConnection(),
            new DBOGridPatch(),
            new DBOGridSnapshot(),
            // Curation tasks
            new DBOCurationTask(),
            new DBOUserStatus(),
            new DBORecordSetValidationStats()
        );
        // @formatter:on
    }

    /**
     * Creates the MigratableTableDAO bean with primary migration objects.
     * The order of this list determines migration order - dependencies first!
     */
    @Bean(initMethod = "initialize")
    @DependsOn("dboBasicDao")
    public MigratableTableDAO migratableTableDAO(
            @Qualifier("migrationJdbcTemplate") JdbcTemplate migrationJdbcTemplate,
            StackConfiguration stackConfiguration) {
        MigratableTableDAOImpl dao = new MigratableTableDAOImpl(migrationJdbcTemplate, stackConfiguration);
        dao.setDatabaseObjectRegister(createMigratableDatabaseObjectRegister());
        return dao;
    }

    /**
     * Creates the list of primary migration objects.
     * Order matters - this list determines the migration order.
     * This list comes from dbo-beans.spb.xml migratableTableDAO property.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private List createMigratableDatabaseObjectRegister() {
        // @formatter:off
        return List.of(
            new DBORealm(),
            new DBOUserGroup(),
            new DBORealmPrincipal(),
            new DBOCertifiedUser(),
            new DBOCredential(),
            new DBOAuthenticatedOn(),
            new DBOPrincipalAlias(),
            new DBONotificationEmail(),
            new DBOUserProfile(),
            new DBOStorageLocation(),
            new DBOFileHandle(),
            new DBOMultipartUpload(),
            new DBOMessageContent(),
            new V2DBOWikiPage(),
            new V2DBOWikiOwner(),
            new DBOActivity(),
            new DBONode(),
            new DBODockerManagedRepositoryName(),
            new DBODockerCommit(),
            // Team-related beans
            new DBOTeam(),
            new DBOMembershipInvitation(),
            new DBOMembershipRequest(),
            new EvaluationDBO(),
            new EvaluationRoundDBO(),
            new EvaluationSubmissionsDBO(),
            new SubmissionDBO(),
            new SubmissionContributorDBO(),
            new SubmissionStatusDBO(),
            new DBOProjectSetting(),
            new DBOProjectStat(),
            new DBOAccessRequirement(),
            new DBOAccessApproval(),
            // in stack-28, nodes, evaluations and teams must migrate before ACLs
            new DBOAccessControlList(),
            new DBOFavorite(),
            new DBOTrashedEntity(),
            new DBOPortal(),
            new DBODoi(),
            new DBOChallenge(),
            new DBOChallengeTeam(),
            new DBOColumnModel(),
            new DBOTableTransaction(),
            new DBOTableRowChange(),
            new DBOTableIdSequence(),
            new DBOTableStatus(),
            new DBOComment(),
            new DBOAsynchJobStatus(),
            new DBOQuizResponse(),
            new DBOVerificationSubmission(),
            new DBOForum(),
            new DBODiscussionThread(),
            new DBODiscussionReply(),
            new DBOSubscription(),
            new DBOBroadcastMessage(),
            new DBOViewType(),
            new DBOThrottleRule(),
            new DBOUnsuccessfulLoginLockout(),
            new DBOResearchProject(),
            new DBORequest(),
            new DBOSubmission(),
            new DBOSubmissionStatus(),
            new DBODataAccessNotification(),
            new DBOFormGroup(),
            new DBOFormData(),
            new DBOOrganization(),
            new DBOJsonSchema(),
            new DBOJsonSchemaVersion(),
            new org.sagebionetworks.repo.model.dbo.file.download.DBODownloadList(),
            new org.sagebionetworks.repo.model.dbo.file.download.v2.DBODownloadList(),
            new DBODataType(),
            new DBOSectorIdentifier(),
            new DBOOAuthClient(),
            new DBOOAuthRefreshToken(),
            new DBOOAuthAccessToken(),
            new DBOPersonalAccessToken(),
            new DBOAuthorizationConsent(),
            new DBOStatisticsMonthlyStatus(),
            new DBOSESNotification(),
            new DBOQuarantinedEmail(),
            new DBOFeatureStatus(),
            new DBOFilesScannerStatus(),
            new DBOMaterializedViewId(),
            new DBOPrincipalOIDCBinding(),
            new DBOOtpSecret(),
            new DBOOtpRecoveryCode(),
            new DBOUserTwoFaStatus(),
            new DBOWebhook(),
            new DBOWebhookVerification(),
            new DBOWebhookAllowedDomain(),
            new DBOAgentRegistration(),
            new DBOAgentSession(),
            new DBOTermsOfServiceRequirements(),
            new DBOTermsOfServiceAgreement(),
            new DBOProjectStorageData(),
            new DBOProjectStorageLimit(),
            new DBOGridSession(),
            new DBOGridReplica(),
            new DBOCurationTask(),
            new DBOUserStatus(),
            // Note: DBOChange must be last! See migration docs.
            new DBOChange()
        );
        // @formatter:on
    }
}
