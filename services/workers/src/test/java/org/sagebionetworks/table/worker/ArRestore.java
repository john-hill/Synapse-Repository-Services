package org.sagebionetworks.table.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import javax.sql.DataSource;

import org.apache.commons.dbcp2.BasicDataSource;
import org.sagebionetworks.repo.model.dbo.DMLUtils;
import org.sagebionetworks.repo.model.dbo.TableMapping;
import org.sagebionetworks.repo.model.dbo.persistence.DBOAccessRequirement;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.BeanPropertySqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

public class ArRestore {

	public static void main(String[] args) {

		
		JdbcTemplate prod459 = new JdbcTemplate(getDataSource("jdbc:mysql://prod-459-db.c5sxx7pot9i8.us-east-1.rds.amazonaws.com/prod459", "prod459user", args[0]));
		
		JdbcTemplate prod458 = new JdbcTemplate(getDataSource("jdbc:mysql://prod-458-db.c5sxx7pot9i8.us-east-1.rds.amazonaws.com/prod458", "prod458user", args[0]));
		NamedParameterJdbcTemplate prod458Named = new NamedParameterJdbcTemplate(prod458.getDataSource());

		DataSourceTransactionManager dstm = new DataSourceTransactionManager(prod458.getDataSource());
		DefaultTransactionDefinition def = new DefaultTransactionDefinition();
		def.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
		def.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
		TransactionTemplate prod458Template = new TransactionTemplate(dstm, def);
		
		Long arIdToRestore = 5592528L;
		Long count = prod459.queryForObject("select count(*) from ACCESS_REQUIREMENT", Long.class);
		System.out.println("ACCESS_REQUIREMENT count 459: "+count);
		
		TableMapping<DBOAccessRequirement> mapping = new DBOAccessRequirement().getTableMapping();
		DBOAccessRequirement ar = prod459.queryForObject("SELECT * FROM ACCESS_REQUIREMENT WHERE ID = ?",mapping, arIdToRestore);
		System.out.println(ar.toString());
		prod458Template.executeWithoutResult(t->{
			String insertSQL = DMLUtils.createInsertStatement(mapping);
			prod458Named.update(insertSQL, new BeanPropertySqlParameterSource(ar));
			DBOAccessRequirement prodAr = prod458.queryForObject("SELECT * FROM ACCESS_REQUIREMENT WHERE ID = ?",mapping, arIdToRestore);
			assertEquals(ar,  prodAr);
			throw new IllegalArgumentException("Undo");
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
	

}
