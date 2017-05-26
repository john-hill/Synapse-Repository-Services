package org.sagebionetworks.repo.manager;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.sagebionetworks.repo.model.ObjectType;
import org.sagebionetworks.repo.model.message.ChangeMessage;
import org.sagebionetworks.repo.model.message.ChangeMessages;
import org.sagebionetworks.repo.model.message.ChangeType;
import org.sagebionetworks.schema.adapter.JSONObjectAdapterException;
import org.sagebionetworks.schema.adapter.org.json.EntityFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.sns.AmazonSNSClient;
import com.amazonaws.services.sns.model.PublishRequest;
import com.google.common.collect.Lists;

public class CheckScopeCRCs {
	
	private static final Long TRASH_ID = new Long(1681355);
	private static final String SELECT_CHILDREN = "SELECT ID, ETAG FROM %1$s WHERE PARENT_ID = ?";
	private static final String SQL = "SELECT PARENT_ID, SUM(crc32(concat(ID,\"-\",ETAG))) AS 'CRC' FROM %1$s group by PARENT_ID";
	JdbcTemplate truth;
	JdbcTemplate replica;
	AmazonSNSClient awsSNSClient;
	String topicArn;

	
	public CheckScopeCRCs(JdbcTemplate truth, JdbcTemplate replica, AmazonSNSClient awsSNSClient, String topicArn) {
		super();
		this.truth = truth;
		this.replica = replica;
		this.awsSNSClient = awsSNSClient;
		this.topicArn =  topicArn;
	}

	public static void main(String[] args) throws JSONObjectAdapterException {
		boolean suppressClose = false;
		JdbcTemplate truth = new JdbcTemplate(new SingleConnectionDataSource(args[0], args[1], args[2], suppressClose));
		JdbcTemplate replica = new JdbcTemplate(new SingleConnectionDataSource(args[3], args[4], args[5], suppressClose));
		AmazonSNSClient awsSNSClient = new AmazonSNSClient(new BasicAWSCredentials(args[6], args[7]));
		String topicArn = args[8];
		CheckScopeCRCs check = new CheckScopeCRCs(truth, replica, awsSNSClient, topicArn);
		check.findDeltas();
	}

	void findDeltas() throws JSONObjectAdapterException {
		// First find the parents out-of-synch.
		System.out.println("Query for truth...");
		Map<Long, Long> truthCRCs = queryForCRCS(truth,"JDONODE");
		System.out.println("Truth count: "+truthCRCs.size());
		System.out.println("Query for replica...");
		Map<Long, Long> replicaCRCs =  queryForCRCS(replica, "ENTITY_REPLICATION");
		System.out.println("Replica count: "+replicaCRCs.size());
		Set<Long> parentOutOfSynch = new HashSet<Long>();
		for(Long truthParentId: truthCRCs.keySet()){
			Long truthCRC = truthCRCs.get(truthParentId);
			Long replicaCRC = replicaCRCs.get(truthParentId);
			if(!truthCRC.equals(replicaCRC)){
				// is the parent in the trash
				if(!isInTrash(truthParentId)){
					System.out.println("Out-of-synch: parentId: "+truthParentId);
					parentOutOfSynch.add(truthParentId);
				}

			}
		}
		System.out.println(""+parentOutOfSynch.size()+" out-of-sych");
		// Broadcast a change for each parent
		int parentCount = 0;
		int totalUpdates = 0;
		for(Long parentId: parentOutOfSynch){
			totalUpdates += broadcastMessageForParentId(parentId);
			parentCount++;
			System.out.println("Parents update: "+parentCount+" of "+parentOutOfSynch.size());
		}
		System.out.println("Total entity changes sent: "+totalUpdates);
	}
	
	/**
	 * Is the given entity in the trash?
	 * @param truthParentId
	 * @return
	 */
	private boolean isInTrash(Long truthParentId) {
		Long benefactor = truth.queryForObject("SELECT getEntityBenefactorId(?)", Long.class, truthParentId);
		return TRASH_ID.equals(benefactor);
	}

	public int broadcastMessageForParentId(Long parentId) throws JSONObjectAdapterException{
		List<ChangeMessage> childrenChanges = calculateChanges(parentId);
		List<List<ChangeMessage>> partitions = Lists.partition(childrenChanges, 500);
		// Send each partition as a message
		for(List<ChangeMessage> batch: partitions){
			ChangeMessages messages = new ChangeMessages();
			messages.setList(batch);
			String json = EntityFactory.createJSONStringForEntity(messages);
			// Send the batch of messages.
			awsSNSClient.publish(new PublishRequest(topicArn, json));
		}
		return childrenChanges.size();
	}
	
	List<ChangeMessage> calculateChanges(Long parentId){
		// Find the children info for both parents
		Set<IdAndEtag> truthChildren = getChildrenInfo(truth, "JDONODE", parentId);
		Set<IdAndEtag> replicaChildren = getChildrenInfo(replica, "ENTITY_REPLICATION", parentId);
		
		List<ChangeMessage> results = new LinkedList<ChangeMessage>();
		Set<Long> truthIds = new HashSet<Long>();
		// find the create/updates
		for(IdAndEtag test: truthChildren){
			if(!replicaChildren.contains(test)){
				results.add(createChange(test, ChangeType.UPDATE));
			}
			truthIds.add(test.getId());
		}
		// find the deletes
		for(IdAndEtag test: replicaChildren){
			if(!truthIds.contains(test.getId())){
				results.add(createChange(test, ChangeType.DELETE));
			}
		}
		return results;
	}
	
	public ChangeMessage createChange(IdAndEtag info, ChangeType type){
		ChangeMessage message = new ChangeMessage();
		message.setChangeNumber(1L);
		message.setChangeType(type);
		message.setObjectId(""+info.getId());
		message.setObjectEtag(info.getEtag());
		message.setObjectType(ObjectType.ENTITY);
		message.setTimestamp(new Date(0));
		return message;
	}
	
	static Set<IdAndEtag> getChildrenInfo(JdbcTemplate template, String tableName, Long parentId){
		String sql = String.format(SELECT_CHILDREN, tableName);
		List<IdAndEtag> list = template.query(sql, new RowMapper<IdAndEtag>(){
			@Override
			public IdAndEtag mapRow(ResultSet rs, int rowNum)
					throws SQLException {
				Long id = rs.getLong("ID");
				String etag = rs.getString("ETAG");
				return new IdAndEtag(id, etag);
			}}, parentId);
		return new HashSet<IdAndEtag>(list);
	}
	
	private static class IdAndEtag {
		Long id;
		String etag;
		public IdAndEtag(Long id, String etag) {
			super();
			this.id = id;
			this.etag = etag;
		}
		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((etag == null) ? 0 : etag.hashCode());
			result = prime * result + ((id == null) ? 0 : id.hashCode());
			return result;
		}
		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			IdAndEtag other = (IdAndEtag) obj;
			if (etag == null) {
				if (other.etag != null)
					return false;
			} else if (!etag.equals(other.etag))
				return false;
			if (id == null) {
				if (other.id != null)
					return false;
			} else if (!id.equals(other.id))
				return false;
			return true;
		}
		public Long getId() {
			return id;
		}
		public String getEtag() {
			return etag;
		}
	}
	
	/**
	 * Query for parentId and CRC32s.
	 * @param template
	 * @param tableName
	 * @return
	 */
	public Map<Long, Long> queryForCRCS(JdbcTemplate template, String tableName){
		final Map<Long, Long> results = new HashMap<Long, Long>();
		String sql = String.format(SQL, tableName);
		template.query(sql, new RowCallbackHandler() {
			@Override
			public void processRow(ResultSet rs) throws SQLException {
				Long id = rs.getLong("PARENT_ID");
				if(id != null){
					Long crc = rs.getLong("CRC");
					results.put(id, crc);
				}
			}
		});
		return results;
	}

}
