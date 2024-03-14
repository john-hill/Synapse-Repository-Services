package org.sagebionetworks;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import javax.sql.DataSource;

import org.apache.commons.dbcp2.BasicDataSource;
import org.sagebionetworks.repo.model.dbo.DMLUtils;
import org.sagebionetworks.repo.model.dbo.TableMapping;
import org.sagebionetworks.repo.model.dbo.dao.dataaccess.DBOAccessRequirementProject;
import org.sagebionetworks.repo.model.dbo.persistence.DBOAccessApproval;
import org.sagebionetworks.repo.model.dbo.persistence.DBOAccessRequirement;
import org.sagebionetworks.repo.model.dbo.persistence.DBOAccessRequirementRevision;
import org.sagebionetworks.repo.model.dbo.persistence.DBOSubjectAccessRequirement;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.BeanPropertySqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

public class RestoreAr {

//	
//	ACCESS_APPROVAL
//	ACCESS_REQUIREMENT_PROJECT
//	ACCESS_REQUIREMENT_REVISION
//	NODE_ACCESS_REQUIREMENT

	public static void main(String[] args) {

		JdbcTemplate staging = new JdbcTemplate(
				getDataSource("jdbc:mysql://prod-492-db.c5sxx7pot9i8.us-east-1.rds.amazonaws.com/prod492", "prod492user", args[0]));

		JdbcTemplate prod = new JdbcTemplate(
				getDataSource("jdbc:mysql://prod-491-db.c5sxx7pot9i8.us-east-1.rds.amazonaws.com/prod491", "prod491user", args[0]));
		
		NamedParameterJdbcTemplate prodNamed = new NamedParameterJdbcTemplate(prod.getDataSource());

		DataSourceTransactionManager dstm = new DataSourceTransactionManager(prod.getDataSource());
		DefaultTransactionDefinition def = new DefaultTransactionDefinition();
		def.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
		def.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
		TransactionTemplate prod458Template = new TransactionTemplate(dstm, def);

		Long arIdToRestore = 5592528L;
		
		TableMapping<DBOAccessRequirement> arMapping = new DBOAccessRequirement().getTableMapping();
		TableMapping<DBOAccessRequirementRevision> revisionMapping = new DBOAccessRequirementRevision().getTableMapping();
		TableMapping<DBOAccessApproval> arApprovalMapping = new DBOAccessApproval().getTableMapping();
		TableMapping<DBOAccessRequirementProject> arProjectMapping = new DBOAccessRequirementProject().getTableMapping();
		TableMapping<DBOSubjectAccessRequirement> arSubjectMapping = new DBOSubjectAccessRequirement().getTableMapping();
		
		DBOAccessRequirement ar = staging.queryForObject("SELECT * FROM ACCESS_REQUIREMENT WHERE ID = ?", arMapping, arIdToRestore);

		List<DBOAccessRequirementRevision> arRevisions = staging.query("SELECT * FROM ACCESS_REQUIREMENT_REVISION WHERE OWNER_ID =?", revisionMapping, arIdToRestore);
		
		List<DBOAccessApproval> arApprovals = staging.query("SELECT * FROM ACCESS_APPROVAL WHERE REQUIREMENT_ID =?", arApprovalMapping, arIdToRestore);
		
		List<DBOAccessRequirementProject> arProjects = staging.query("SELECT * FROM ACCESS_REQUIREMENT_PROJECT WHERE AR_ID =?", arProjectMapping, arIdToRestore);
		
		List<DBOSubjectAccessRequirement> arSubjects = staging.query("SELECT * FROM NODE_ACCESS_REQUIREMENT WHERE REQUIREMENT_ID =?", arSubjectMapping, arIdToRestore);
		
		prod458Template.executeWithoutResult(t -> {
			String insertSQL = DMLUtils.createInsertStatement(arMapping);
			prodNamed.update(insertSQL, new BeanPropertySqlParameterSource(ar));
			
			DBOAccessRequirement prodAr = prod.queryForObject("SELECT * FROM ACCESS_REQUIREMENT WHERE ID = ?", arMapping, arIdToRestore);
			
			assertEquals(ar, prodAr);
			
			System.out.println("AR Created");
			
			for (DBOAccessRequirementRevision revision : arRevisions) {
				insertSQL = DMLUtils.createInsertStatement(revisionMapping);
				prodNamed.update(insertSQL, new BeanPropertySqlParameterSource(revision));
			}
			
			List<DBOAccessRequirementRevision> prodArRevisions = prod.query("SELECT * FROM ACCESS_REQUIREMENT_REVISION WHERE OWNER_ID =?", revisionMapping, arIdToRestore);
			
			assertEquals(arRevisions, prodArRevisions);
			
			System.out.println("AR Revisions Created");
			
			for (DBOAccessRequirementProject project : arProjects) {
				insertSQL = DMLUtils.createInsertStatement(arProjectMapping);
				prodNamed.update(insertSQL, new BeanPropertySqlParameterSource(project));
			}
			
			List<DBOAccessRequirementProject> prodArProjects = prod.query("SELECT * FROM ACCESS_REQUIREMENT_PROJECT WHERE AR_ID =?", arProjectMapping, arIdToRestore);
			
			assertEquals(arProjects, prodArProjects);
			
			System.out.println("AR Projects Created");
			
			for (DBOSubjectAccessRequirement subject : arSubjects) {
				insertSQL = DMLUtils.createInsertStatement(arSubjectMapping);
				prodNamed.update(insertSQL, new BeanPropertySqlParameterSource(subject));
			}
			
			List<DBOSubjectAccessRequirement> prodArSubjects = prod.query("SELECT * FROM NODE_ACCESS_REQUIREMENT WHERE REQUIREMENT_ID =?", arSubjectMapping, arIdToRestore);
			
			assertEquals(arSubjects, prodArSubjects);
			
			System.out.println("AR Subjects Created");
			
			for (DBOAccessApproval approval : arApprovals) {
				insertSQL = DMLUtils.createInsertStatement(arApprovalMapping);
				prodNamed.update(insertSQL, new BeanPropertySqlParameterSource(approval));
				System.out.println("AR Approval Created: " + approval.getId());
			}
			
			List<DBOAccessApproval> prodArApprovals = prod.query("SELECT * FROM ACCESS_APPROVAL WHERE REQUIREMENT_ID =?", arApprovalMapping, arIdToRestore);
			
			assertEquals(arApprovals, prodArApprovals);
			
			System.out.println("AR Approvals Created");
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