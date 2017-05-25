package org.sagebionetworks.repo.manager;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
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
	private static final String SELECT_CHILDREN = "SELECT ID, ETAG FROM JDONODE WHERE PARENT_ID = ?";
	private static final String SQL = "SELECT PARENT_ID, SUM(crc32(concat(ID,\"-\",ETAG))) AS 'CRC' from %1$s group by PARENT_ID";
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
		List<ChangeMessage> childrenChanges = createMessage(parentId);
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
	
	/**
	 * Create a change message for each child of the given parent.
	 * @param parentId
	 * @return
	 */
	List<ChangeMessage> createMessage(Long parentId){
		return truth.query(SELECT_CHILDREN, new RowMapper<ChangeMessage>(){

			@Override
			public ChangeMessage mapRow(ResultSet rs, int rowNum)
					throws SQLException {
				Long id = rs.getLong("ID");
				String etag = rs.getString("ETAG");
				ChangeMessage message = new ChangeMessage();
				message.setChangeNumber(1L);
				message.setChangeType(ChangeType.UPDATE);
				message.setObjectId(""+id);
				message.setObjectEtag(etag);
				message.setObjectType(ObjectType.ENTITY);
				message.setTimestamp(new Date(0));
				return message;
			}}, parentId);
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
