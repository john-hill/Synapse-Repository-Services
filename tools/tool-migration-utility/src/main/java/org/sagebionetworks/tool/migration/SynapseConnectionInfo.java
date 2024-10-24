package org.sagebionetworks.tool.migration;

/**
 * Holds all of the information needed to establish a Synapse Connection
 * @author John
 *
 */
public class SynapseConnectionInfo {
	
	/**
	 * The only public constructor.
	 * @param authenticationEndPoint
	 * @param repositoryEndPoint
	 * @param adminUsername
	 * @param adminPassword
	 */
	public SynapseConnectionInfo(String authenticationEndPoint,
			String repositoryEndPoint, String adminUsername,
			String adminPassword,
			// remove after 0.12->0.13 migration
			String stackIamId,
			String stackIamKey,
			String sharedS3BackupBucket,
			String crowdEndpoint,
			String crowdApplicationKey
			) {
		super();
		this.authenticationEndPoint = authenticationEndPoint;
		this.repositoryEndPoint = repositoryEndPoint;
		this.adminUsername = adminUsername;
		this.adminPassword = adminPassword;
		
		// remove after 0.12->0.13 migration
		this.stackIamId = stackIamId;
		this.stackIamKey = stackIamKey;
		this.sharedS3BackupBucket = sharedS3BackupBucket;
		this.crowdEndpoint = crowdEndpoint;
		this.crowdApplicationKey = crowdApplicationKey;
	}
	
	private String authenticationEndPoint;
	private String repositoryEndPoint;
	private String adminUsername;
	private String adminPassword;
	
	// remove after 0.12->0.13 migration
	private String stackIamId;
	private String stackIamKey;
	private String sharedS3BackupBucket;
	private String crowdEndpoint;
	private String crowdApplicationKey;
	
	
	public String getAuthenticationEndPoint() {
		return authenticationEndPoint;
	}
	public String getRepositoryEndPoint() {
		return repositoryEndPoint;
	}
	public String getAdminUsername() {
		return adminUsername;
	}
	public String getAdminPassword() {
		return adminPassword;
	}
	public String getStackIamId() {
		return stackIamId;
	}
	public String getStackIamKey() {
		return stackIamKey;
	}
	public String getSharedS3BackupBucket() {
		return sharedS3BackupBucket;
	}
	public String getCrowdEndpoint() {
		return crowdEndpoint;
	}
	public String getCrowdApplicationKey() {
		return crowdApplicationKey;
	}
	@Override
	public String toString() {
		return "SynapseConnectionInfo [authenticationEndPoint="
				+ authenticationEndPoint + ", repositoryEndPoint="
				+ repositoryEndPoint + ", adminUsername=" + adminUsername
				+ ", adminPassword=" + adminPassword + ", stackIamId="
				+ stackIamId + ", stackIamKey=" + stackIamKey
				+ ", sharedS3BackupBucket=" + sharedS3BackupBucket
				+ ", crowdEndpoint=" + crowdEndpoint + ", crowdApplicationKey="
				+ crowdApplicationKey + "]";
	}

}
