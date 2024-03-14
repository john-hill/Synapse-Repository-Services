package org.sagebionetworks.table.worker;

import java.sql.ResultSet;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.apache.commons.dbcp2.BasicDataSource;
import org.sagebionetworks.StackConstants;
import org.sagebionetworks.repo.manager.table.TableManagerSupportImpl;
import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.EntityType;
import org.sagebionetworks.repo.model.ObjectType;
import org.sagebionetworks.repo.model.message.ChangeType;
import org.sagebionetworks.repo.model.message.LocalStackChangeMesssage;
import org.sagebionetworks.schema.adapter.JSONObjectAdapterException;
import org.sagebionetworks.schema.adapter.org.json.EntityFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.EnvironmentVariableCredentialsProvider;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.sns.AmazonSNS;
import com.amazonaws.services.sns.AmazonSNSClient;
import com.amazonaws.services.sns.model.PublishRequest;

public class TablesRepairMainV3 {

	public static void main(String[] args) {
		String dbUrl = args[0];
		String dbUser = args[1];
		String dbPass = args[2];
		String stack = args[3];
		
		if(!dbUrl.contains(stack)) {
			throw new IllegalArgumentException("The DB url does not contain the stack number");
		}
		
		System.out.println(String.format("Using DB url: '%s'", dbUrl));
		

	
		JdbcTemplate jdbcTemplate = new JdbcTemplate(getDataSource(dbUrl, dbUser, dbPass));

		Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM TABLE_STATUS", Long.class);
		System.out.println(String.format("Table status count: %s", count));

		AWSCredentialsProvider credentialProvider = new EnvironmentVariableCredentialsProvider();

		AmazonSNS snsClient = AmazonSNSClient.builder().withRegion(Regions.US_EAST_1)
				.withCredentials(credentialProvider).build();
		Map<ObjectType, String> topicArns = createTopicArns(stack, ObjectType.TABLE,
				ObjectType.MATERIALIZED_VIEW, ObjectType.ENTITY_VIEW);

		String sql = "WITH \r\n"
				+ " ALL_TABLES AS (\r\n"
				+ "	SELECT \r\n"
				+ "		N.ID, \r\n"
				+ "		N.NODE_TYPE, \r\n"
				+ "		CASE WHEN N.CURRENT_REV_NUM = R.NUMBER THEN -1 ELSE R.NUMBER END AS VERSION\r\n"
				+ "	FROM JDONODE N JOIN JDOREVISION R ON (N.ID = R.OWNER_NODE_ID)\r\n"
				+ "	WHERE N.NODE_TYPE IN ('table', 'entityview', 'materializedview', 'dataset', 'datasetcollection', 'submissionview') \r\n"
				+ "    AND getEntityBenefactorId(N.PARENT_ID) <> 1681355\r\n"
				+ "),\r\n"
				+ " SCHEMA_INFO AS (\r\n"
				+ "	SELECT distinct OBJECT_ID, OBJECT_VERSION FROM BOUND_COLUMN_ORDINAL\r\n"
				+ " )\r\n"
				+ "SELECT T.ID, T.NODE_TYPE, T.VERSION, S.STATE\r\n"
				+ "	FROM ALL_TABLES T LEFT JOIN TABLE_STATUS S ON (T.ID = S.TABLE_ID AND T.VERSION = S.VERSION) JOIN SCHEMA_INFO I ON (T.ID = I.OBJECT_ID AND T.VERSION = I.OBJECT_VERSION)\r\n"
				+ "	WHERE S.TABLE_ID IS NULL";

		List<LocalStackChangeMesssage> toPush = jdbcTemplate.query(sql, MAPPER);
		System.out.println("Total number of tables/views that are missing table status: " + toPush.size());
		toPush.stream().forEach((t) -> {
			String arn = topicArns.get(t.getObjectType());
			if (arn == null) {
				throw new IllegalStateException("Did not find an ARN for: " + t.getObjectType());
			}
			System.out.println(String.format("Pusing message for ID: '%s' version: '%s' type: '%s'", t.getObjectId(), t.getObjectVersion(), t.getObjectType()));
			snsClient.publish(new PublishRequest(arn, toJson(t)));
		});
	}

	private static DataSource getDataSource(String dbUrl, String dbUser, String dbPass) {
		BasicDataSource dataSource = new BasicDataSource();
		dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
		dataSource.setUsername(dbUser);
		dataSource.setPassword(dbPass);
		dataSource.setUrl(dbUrl);
		return dataSource;
	}

	private static RowMapper<LocalStackChangeMesssage> MAPPER = (ResultSet rs, int rowNum) -> {
		Long id = rs.getLong("ID");
		Long version = rs.getLong("VERSION");
		if(Long.valueOf(-1L).equals(version)) {
			version = null;
		}
		EntityType type = EntityType.valueOf(rs.getString("NODE_TYPE"));
		ObjectType objectType = TableManagerSupportImpl.getObjectTypeForEntityType(type);
		return new LocalStackChangeMesssage().setChangeNumber(-42L).setObjectId(id.toString()).setObjectVersion(version)
				.setObjectType(objectType).setChangeType(ChangeType.UPDATE).setTimestamp(new Date())
				.setUserId(AuthorizationConstants.BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId());
	};

	/**
	 * Build the the topic ARNs for each object type.
	 * 
	 * @param stack
	 * @param snsClient
	 * @param types
	 * @return
	 */
	private static Map<ObjectType, String> createTopicArns(String stack, ObjectType... types) {
		Map<ObjectType, String> map = new HashMap<>(types.length);
		for (ObjectType type : types) {
			String name = String.format(StackConstants.QUEUE_AND_TOPIC_NAME_TEMPLATE, "prod", stack, type.name());
			String arn = "arn:aws:sns:us-east-1:325565585839:"+name;
			System.out.println(String.format("Publishing to topic arn: '%s'", arn));
			map.put(type, arn);
		}
		return map;
	}
	
	private static String toJson(LocalStackChangeMesssage message) {
		try {
			return EntityFactory.createJSONStringForEntity(message);
		} catch (JSONObjectAdapterException e) {
			throw new RuntimeException(e);
		}
	}
}
