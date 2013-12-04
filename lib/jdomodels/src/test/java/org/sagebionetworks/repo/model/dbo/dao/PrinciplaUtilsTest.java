package org.sagebionetworks.repo.model.dbo.dao;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Date;

import org.junit.Test;
import org.sagebionetworks.repo.model.Principal;
import org.sagebionetworks.repo.model.dbo.persistence.DBOPrincipal;
import org.sagebionetworks.repo.model.dbo.persistence.DBOPrincipalBackup;

/**
 * Test for translation of DBOs, DTOs, and backup objects.
 * @author John
 *
 */
public class PrinciplaUtilsTest {
	
	/**
	 * This test is only need between stack 23 and 24.  We can remove it when stack 24 goes live into production.
	 * 
	 */
	@Test
	public void testUserMigrate23To24(){
		// In stack 23 users have an email address as a 'name'.
		DBOPrincipalBackup backup = new DBOPrincipalBackup();
		backup.setId(123l);
		backup.setCreationDate(new Date());
		backup.setEtag("etag1");
		backup.setIsIndividual(true);
		backup.setName("Somebody@domain.com");
		
		// Now translate this backup object into the current DBO
		DBOPrincipal dbo = PrincipalUtils.createDatabaseObjectFromBackup(backup);
		assertNotNull(dbo);
		assertTrue(dbo.getIsIndividual());
		assertEquals("The old 'name' for a user should now be an email", backup.getName().toLowerCase(), dbo.getEmail());
		assertTrue("All users migrated from stack 23 to 24 must provide a new principalName", dbo.getMustProvideNewPrincipalName());
		assertEquals("Migrated users should have their 'id' as a principalName", "123", dbo.getPrincipalNameDisplay());
		assertEquals("Migrated users should have their 'id' as a principalNameUnique", "123", dbo.getPrincipalNameUnique());
		// These fields should just be copied
		assertEquals(backup.getEtag(), dbo.getEtag());
		assertEquals(backup.getCreationDate(), dbo.getCreationDate());
		assertEquals(backup.getId(), dbo.getId());
		assertEquals(backup.getIsIndividual(), dbo.getIsIndividual());
	}
	
	/**
	 * This test is only need between stack 23 and 24.  We can remove it when stack 24 goes live into production.
	 * 
	 */
	@Test
	public void testTeamMigrate23To24(){
		// In stack 23 teams have a valid principalName as a 'name'.
		DBOPrincipalBackup backup = new DBOPrincipalBackup();
		backup.setId(123l);
		backup.setCreationDate(new Date());
		backup.setEtag("etag1");
		backup.setIsIndividual(false);
		String teamPrincipalNameDispaly = "Best Team Ever";
		String teamPrincipalNameUnique = "bestteamever";
		backup.setName(teamPrincipalNameDispaly);
		
		// Now translate this backup object into the current DBO
		DBOPrincipal dbo = PrincipalUtils.createDatabaseObjectFromBackup(backup);
		assertNotNull(dbo);
		assertFalse(dbo.getIsIndividual());
		assertEquals("Teams do not have emails, so for now we fill it in with their IDs.", "123", dbo.getEmail());
		assertFalse("All teams migrated from stack 23 to 24 do not need to provide a new principalName", dbo.getMustProvideNewPrincipalName());
		assertEquals("Migrated teams should have their 'name' as a 'principalNameDisplay'", teamPrincipalNameDispaly, dbo.getPrincipalNameDisplay());
		assertEquals("Migrated teams should have their a unique name using lower-case letters and numbers ",teamPrincipalNameUnique, dbo.getPrincipalNameUnique());
		// These fields should just be copied
		assertEquals(backup.getEtag(), dbo.getEtag());
		assertEquals(backup.getCreationDate(), dbo.getCreationDate());
		assertEquals(backup.getId(), dbo.getId());
		assertEquals(backup.getIsIndividual(), dbo.getIsIndividual());
	}
	
	/**
	 * Test that we make the backup->dbo and dbo->backup round trip on current user correctly.
	 */
	@Test
	public void testUserBackupDBORoundTrip(){
		// Start with a DBO as that is how a backup will start
		DBOPrincipal dbo = new DBOPrincipal();
		dbo.setId(123l);
		dbo.setEmail("somebody@domain.com");
		dbo.setCreationDate(new Date());
		dbo.setEtag("etag");
		dbo.setIsIndividual(true);
		dbo.setMustProvideNewPrincipalName(true);
		dbo.setPrincipalNameDisplay("jamesBond");
		dbo.setPrincipalNameUnique("jamesbond");
		// To backup
		DBOPrincipalBackup backup = PrincipalUtils.createBackupFromDatabaseObject(dbo);
		assertNotNull(backup);
		// New backups must never have a name (this check can be removed when 'name' is removed)
		assertEquals("The backup of a current DBO must not contain a 'name'",null, backup.getName());
		// Now make the round trip
		DBOPrincipal clone = PrincipalUtils.createDatabaseObjectFromBackup(backup);
		assertEquals(dbo, clone);
	}
	
	/**
	 * Test that we make the backup->dbo and dbo->backup round trip on current team correctly.
	 */
	@Test
	public void testTeamBackupDBORoundTrip(){
		// Start with a DBO as that is how a backup will start
		DBOPrincipal dbo = new DBOPrincipal();
		dbo.setId(123l);
		// Teams have their ID as an email for now.  This will be removed from principal in 
		dbo.setEmail("123");
		dbo.setCreationDate(new Date());
		dbo.setEtag("etag");
		dbo.setIsIndividual(false);
		dbo.setMustProvideNewPrincipalName(false);
		dbo.setPrincipalNameDisplay("Best Team Ever");
		dbo.setPrincipalNameUnique("bestteamever");
		// To backup
		DBOPrincipalBackup backup = PrincipalUtils.createBackupFromDatabaseObject(dbo);
		assertNotNull(backup);
		// New backups must never have a name (this check can be removed when 'name' is removed)
		assertEquals("The backup of a current DBO must not contain a 'name'",null, backup.getName());
		// Now make the round trip
		DBOPrincipal clone = PrincipalUtils.createDatabaseObjectFromBackup(backup);
		assertEquals(dbo, clone);
	}
	
	@Test (expected=IllegalArgumentException.class)
	public void testCreateDatabaseObjectFromBackupInvalidName(){
		// Start with a DBO as that is how a backup will start
		DBOPrincipal dbo = new DBOPrincipal();
		dbo.setId(123l);
		// Teams have their ID as an email for now.  This will be removed from principal in 
		dbo.setEmail("123");
		dbo.setCreationDate(new Date());
		dbo.setEtag("etag");
		dbo.setIsIndividual(false);
		dbo.setMustProvideNewPrincipalName(false);
		dbo.setPrincipalNameDisplay("this is *invalid*");
		dbo.setPrincipalNameUnique("bestteamever");
		// To backup
		DBOPrincipalBackup backup = PrincipalUtils.createBackupFromDatabaseObject(dbo);
		assertNotNull(backup);
		// New backups must never have a name (this check can be removed when 'name' is removed)
		assertEquals("The backup of a current DBO must not contain a 'name'",null, backup.getName());
		// Now make the round trip
		DBOPrincipal clone = PrincipalUtils.createDatabaseObjectFromBackup(backup);
		assertEquals(dbo, clone);
	}

	@Test
	public void testUserRoundtrip() throws Exception {
		Principal dto = new Principal();
		dto.setId("1001");
		dto.setEmail("Foo@domain.org");
		dto.setPrincipalName("jamesBond007");
		dto.setCreationDate(new Date());
		dto.setIsIndividual(true);
		dto.setEtag("Bloop");
		// to DBO
		DBOPrincipal dbo = PrincipalUtils.createDBO(dto);
		assertEquals("jamesbond007", dbo.getPrincipalNameUnique());
		Principal clone = PrincipalUtils.createDTO(dbo);
		// the email will be converted to lower case
		dto.setEmail(dto.getEmail().toLowerCase());
		assertEquals(dto, clone);
	}
	
	@Test
	public void testTeamRoundtrip() throws Exception {
		Principal dto = new Principal();
		dto.setId("1001");
		dto.setEmail("1001");
		dto.setPrincipalName("Best group ever");
		dto.setCreationDate(new Date());
		dto.setIsIndividual(false);
		dto.setEtag("Bloop");
		// to DBO
		DBOPrincipal dbo = PrincipalUtils.createDBO(dto);
		Principal clone = PrincipalUtils.createDTO(dbo);
		assertEquals(dto, clone);
	}
	
	@Test (expected = IllegalArgumentException.class)
	public void testValidatePrincipalNameNull(){
		PrincipalUtils.validatePrincipalName(null, true);
	}
	
	@Test
	public void testValidatePrincipalNameUser(){
		PrincipalUtils.validatePrincipalName("1234567890.a-b_cdefghijklmnopqrstuvwxyz", true);
	}
	
	@Test
	public void testValidatePrincipalUserSpaces(){
		try{
			PrincipalUtils.validatePrincipalName("has spaces", true);
			fail("should have failed because it contains spaces.");
		}catch(IllegalArgumentException e){
			assertTrue(e.getMessage().contains("letters"));
			assertTrue(e.getMessage().contains("numbers"));
			assertTrue(e.getMessage().contains("underscore"));
			assertTrue(e.getMessage().contains("dash"));
			assertTrue(e.getMessage().contains("dot"));
			assertTrue(e.getMessage().contains("3 characters long"));
		}
	}
	
	@Test (expected=IllegalArgumentException.class)
	public void testValidatePrincipalUserTooShort(){
		PrincipalUtils.validatePrincipalName("12", true);
	}

	@Test
	public void testValidatePrincipalUserLongEnough(){
		PrincipalUtils.validatePrincipalName("123", true);
	}
	
	
	@Test (expected=IllegalArgumentException.class)
	public void testValidatePrincipalUserOtherChars(){
		PrincipalUtils.validatePrincipalName("has!@#$%^&*()otherchars", true);
	}

	@Test
	public void testValidatePrincipalNameTeam(){
		PrincipalUtils.validatePrincipalName("1234567890.a-b_c defghijklmnopqrstuvwxyzABCDEFGHIJKLMNOP", false);
	}
	
	@Test
	public void testValidatePrincipalTeamAt(){
		try{
			PrincipalUtils.validatePrincipalName("has@chars", false);
			fail("should have failed because it contains spaces.");
		}catch(IllegalArgumentException e){
			assertTrue(e.getMessage().contains("letters"));
			assertTrue(e.getMessage().contains("numbers"));
			assertTrue(e.getMessage().contains("underscore"));
			assertTrue(e.getMessage().contains("dash"));
			assertTrue(e.getMessage().contains("dot"));
			assertTrue(e.getMessage().contains("space"));
			assertTrue(e.getMessage().contains("3 characters long"));
		}
	}
	
	@Test (expected=IllegalArgumentException.class)
	public void testValidatePrincipalTeamTooShort(){
		PrincipalUtils.validatePrincipalName("12", false);
	}

	@Test
	public void testValidatePrincipalTeamLongEnough(){
		PrincipalUtils.validatePrincipalName("123", false);
	}
	
	
	@Test (expected=IllegalArgumentException.class)
	public void testValidatePrincipalTeamOtherChars(){
		PrincipalUtils.validatePrincipalName("has!@#$%^&*()otherchars", false);
	}
	
	@Test
	public void testGetUniquePrincipalNameCase(){
		String input = "BigTop";
		String expected = "bigtop";
		String result = PrincipalUtils.getUniquePrincipalName(input);
		assertEquals(expected, result);
	}
	
	@Test
	public void testGetUniquePrincipalNameSpace(){
		String input = "Big Top";
		String expected = "bigtop";
		String result = PrincipalUtils.getUniquePrincipalName(input);
		assertEquals(expected, result);
	}
	
	@Test
	public void testGetUniquePrincipalNameDash(){
		String input = "Big-Top";
		String expected = "bigtop";
		String result = PrincipalUtils.getUniquePrincipalName(input);
		assertEquals(expected, result);
	}
	
	@Test
	public void testGetUniquePrincipalNameAll(){
		String input = "1.2 3-4_567890AbCdEfGhIJklmnoPqRSTUvwxyz";
		String expected = "1234567890abcdefghijklmnopqrstuvwxyz";
		String result = PrincipalUtils.getUniquePrincipalName(input);
		assertEquals(expected, result);
	}
}
