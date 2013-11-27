package org.sagebionetworks.repo.model.dbo.dao;

import java.util.LinkedList;
import java.util.List;

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
				dbo.setPrincipalNameLower(backup.getId().toString().toLowerCase());
				// All migrated users must provide a new principal name.
				dbo.setMustProvideNewPrincipalName(true);
			}else{
				// Currently teams do not have emails. Later we will move the email field from
				// this table.  For now the email is non-null and must unique so we use the ID of
				// the team to meet this criteria.
				dbo.setEmail(backup.getId().toString());
				// For teams the old 'name' can be used as the principal name of the team
				dbo.setPrincipalNameDisplay(backup.getName());
				dbo.setPrincipalNameLower(backup.getName().toLowerCase());
				// Teams do not need to change their names.
				dbo.setMustProvideNewPrincipalName(false);
			}
		}else{
			// If 'name' null this is a new object so we just convert all of the values as the are.
			// For this case we just copy over the common fields
			dbo.setEmail(backup.getEmail());
			dbo.setMustProvideNewPrincipalName(backup.getMustProvideNewPrincipalName());
			dbo.setPrincipalNameDisplay(backup.getPrincipalDisplay());
			dbo.setPrincipalNameLower(backup.getPrincipalNameLower());
		}

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
		backup.setPrincipalNameLower(dbo.getPrincipalNameLower());
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
		dbo.setPrincipalNameLower(dto.getPrincipalName().toLowerCase());
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
	
	

}
