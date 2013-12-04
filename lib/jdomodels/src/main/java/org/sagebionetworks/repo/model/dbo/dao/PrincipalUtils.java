package org.sagebionetworks.repo.model.dbo.dao;

import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.sagebionetworks.repo.model.Principal;
import org.sagebionetworks.repo.model.dbo.persistence.DBOPrincipal;
import org.sagebionetworks.repo.model.dbo.persistence.DBOPrincipalBackup;

/**
 * This utility converts between DBOs, DTOs, and Backup objects.
 * 
 * @author John
 *
 */
public class PrincipalUtils {
	
	public static String USER_PRINCIPAL_NAME_REGEX = "^[a-z0-9._-]{3,}";
	public static String TEAM_PRINCIPAL_NAME_REGEX = "^[a-z0-9 ._-]{3,}";
	private static Pattern USER_PRINCIPAL_NAME_PATTERN = Pattern.compile(USER_PRINCIPAL_NAME_REGEX);
	private static Pattern TEAM_PRINCIPAL_NAME_PATTERN = Pattern.compile(TEAM_PRINCIPAL_NAME_REGEX);
	// Used to replace all characters expect letters and numbers.
	private static Pattern PRINICPAL_UNIQUENESS_REPLACE_PATTERN  = Pattern.compile("[^a-z0-9]");
	
	/**
	 * Translate from a backup to a DBO.
	 * @param backup
	 * @return
	 */
	public static DBOPrincipal createDatabaseObjectFromBackup(DBOPrincipalBackup backup) {
		if(backup == null) throw new IllegalArgumentException("Backup cannot be null");
		// Set all of the fields that have not change.
		DBOPrincipal dbo = new DBOPrincipal();
		dbo.setId(backup.getId());
		dbo.setIsIndividual(backup.getIsIndividual());
		dbo.setCreationDate(backup.getCreationDate());
		dbo.setEtag(backup.getEtag());
		
		// If a backup has a 'name' then we are migrating from 23 to 24
		if(backup.getName() != null){
			if(backup.getIsIndividual()){
				// For users the old 'name' is an email address to lower case.
				dbo.setEmail(backup.getName().toLowerCase());
				// set the user's principalName to be their ID and require the user to change it.
				dbo.setPrincipalNameDisplay(backup.getId().toString());
				dbo.setPrincipalNameUnique(getUniquePrincipalName(backup.getId().toString()));
				// All migrated users must provide a new principal name.
				dbo.setMustProvideNewPrincipalName(true);
			}else{
				// Currently teams do not have emails. Later we will move the email field from
				// this table.  For now the email is non-null and must unique so we use the ID of
				// the team to meet this criteria.
				dbo.setEmail(backup.getId().toString());
				// For teams the old 'name' can be used as the principal name of the team
				dbo.setPrincipalNameDisplay(backup.getName());
				dbo.setPrincipalNameUnique(getUniquePrincipalName(backup.getName()));
				// Teams do not need to change their names.
				dbo.setMustProvideNewPrincipalName(false);
			}
		}else{
			// If 'name' null this is a new object so we just convert all of the values as the are.
			// For this case we just copy over the common fields
			dbo.setEmail(backup.getEmail());
			dbo.setMustProvideNewPrincipalName(backup.getMustProvideNewPrincipalName());
			dbo.setPrincipalNameDisplay(backup.getPrincipalDisplay());
			dbo.setPrincipalNameUnique(backup.getPrincipalNameUnique());
		}

		// Validate the resulting principal name
		validatePrincipalName(dbo.getPrincipalNameDisplay(), dbo.getIsIndividual());
		return dbo;
	}

	/**
	 * Convert from the current DBO into a current backup.
	 * @param dbo
	 * @return
	 */
	public static DBOPrincipalBackup createBackupFromDatabaseObject(DBOPrincipal dbo) {
		DBOPrincipalBackup backup = new DBOPrincipalBackup();
		backup.setCreationDate(dbo.getCreationDate());
		backup.setEmail(dbo.getEmail());
		backup.setEtag(dbo.getEtag());
		backup.setId(dbo.getId());
		backup.setIsIndividual(dbo.getIsIndividual());
		backup.setMustProvideNewPrincipalName(dbo.getMustProvideNewPrincipalName());
		backup.setPrincipalDisplay(dbo.getPrincipalNameDisplay());
		backup.setPrincipalNameUnique(dbo.getPrincipalNameUnique());
		return backup;
	}
	
	/**
	 * Create a DBO from a DTO
	 * @param dto
	 * @return
	 */
	public static DBOPrincipal createDBO(Principal dto) {
		DBOPrincipal dbo = new DBOPrincipal();
		if (dto.getId()==null) {
			dbo.setId(null);
		} else {
			dbo.setId(Long.parseLong(dto.getId()));
		}
		dbo.setCreationDate(dto.getCreationDate());
		dbo.setIsIndividual(dto.getIsIndividual());
		dbo.setPrincipalNameDisplay(dto.getPrincipalName());
		dbo.setPrincipalNameUnique(getUniquePrincipalName(dto.getPrincipalName()));
		dbo.setMustProvideNewPrincipalName(false);
		dbo.setEmail(dto.getEmail().toLowerCase());
		dbo.setEtag(dto.getEtag());
		return dbo;
	}
	
	/**
	 * Create a DTO from a DBO
	 * @param dbo
	 * @return
	 */
	public static Principal createDTO(DBOPrincipal dbo) {
		Principal dto = new Principal();
		if (dbo.getId()==null) {
			dto.setId(null); 
		} else {
			dto.setId(dbo.getId().toString());
		}
		dto.setCreationDate(dbo.getCreationDate());
		dto.setIsIndividual(dbo.getIsIndividual());
		dto.setPrincipalName(dbo.getPrincipalNameDisplay());
		dto.setEmail(dbo.getEmail());
		dto.setEtag(dbo.getEtag());
		return dto;
	}
	
	/**
	 * Create a list of DTOs from a list of DBOs
	 * @param dbos
	 * @return
	 */
	public static List<Principal> createDTOs(List<DBOPrincipal> dbos) {
		List<Principal> dtos = new LinkedList<Principal>();
		for (DBOPrincipal dbo : dbos) {
			dtos.add(createDTO(dbo));
		}
		return dtos;
	}
	
	/**
	 * Validate a principal name.
	 * @param name
	 * @param isIndividual
	 */
	public static void validatePrincipalName(String name, boolean isIndividual){
		if(name == null) throw new IllegalArgumentException("Name cannot be null");
		// validate the lower case version of the string.
		String lower = name.toLowerCase();
		if(isIndividual){
	        Matcher m = USER_PRINCIPAL_NAME_PATTERN.matcher(lower);
	        if(!m.matches()){
	        	throw new IllegalArgumentException("User names can only contain letters, numbers, dot (.), dash (-) and underscore (_) and must be at least 3 characters long.");
	        }
		}else{
	        Matcher m = TEAM_PRINCIPAL_NAME_PATTERN.matcher(lower);
	        if(!m.matches()){
	        	throw new IllegalArgumentException("Team names can only contain letters, numbers, spaces, dot (.), dash (-), underscore (_) and must be at least 3 characters long.");
	        }
		}
	}
	
	/**
	 * Get the string that will be used for a uniqueness check of principal names. 
	 * Only lower case letters and numbers contribute to the uniqueness of a principal name.
	 * All other characters (-,., ,_) are ignored.
	 * 
	 * @param inputName
	 * @return
	 */
	public static String getUniquePrincipalName(String inputName){
		if(inputName == null) throw new IllegalArgumentException("Name cannot be null");
		// Case does not contribute to uniqueness
		String lower = inputName.toLowerCase();
		// Only letters and numbers contribute to the uniqueness
		Matcher m = PRINICPAL_UNIQUENESS_REPLACE_PATTERN.matcher(lower);
		// Replace all non-letters and numbers with empty strings
		return m.replaceAll("");
	}
	

}
