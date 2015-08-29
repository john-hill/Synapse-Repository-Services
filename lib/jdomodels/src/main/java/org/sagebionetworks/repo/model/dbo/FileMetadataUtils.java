package org.sagebionetworks.repo.model.dbo;

import java.net.MalformedURLException;
import java.net.URL;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import org.sagebionetworks.repo.model.backup.FileHandleBackup;
import org.sagebionetworks.repo.model.dbo.persistence.DBOFileHandle;
import org.sagebionetworks.repo.model.dbo.persistence.DBOFileHandle.MetadataType;
import org.sagebionetworks.repo.model.dbo.persistence.DBOFileHandleAssociation;
import org.sagebionetworks.repo.model.file.ExternalFileHandle;
import org.sagebionetworks.repo.model.file.FileHandle;
import org.sagebionetworks.repo.model.file.FileHandleAssociation;
import org.sagebionetworks.repo.model.file.HasPreviewId;
import org.sagebionetworks.repo.model.file.PreviewFileHandle;
import org.sagebionetworks.repo.model.file.S3FileHandle;
import org.sagebionetworks.repo.model.file.S3FileHandleInterface;
import org.sagebionetworks.util.ValidateArgument;

/**
 * Translates between DBOs and DTOs.
 * @author John
 *
 */
public class FileMetadataUtils {

	/**
	 * Convert abstract DTO to the DBO.
	 * @param dto
	 * @return
	 * @throws MalformedURLException 
	 */
	public static DBOFileHandle createDBOFromDTO(FileHandle fileHandle) {
		if (fileHandle == null)
			throw new IllegalArgumentException("DTO cannot be null");

		DBOFileHandle dbo = new DBOFileHandle();

		if (fileHandle instanceof ExternalFileHandle) {
			dbo.setMetadataType(MetadataType.EXTERNAL);
			createDBOFromDTO(dbo, (ExternalFileHandle) fileHandle);
		} else if (fileHandle instanceof S3FileHandle) {
			dbo.setMetadataType(MetadataType.S3);
		} else if (fileHandle instanceof PreviewFileHandle) {
			dbo.setMetadataType(MetadataType.PREVIEW);
		} else {
			throw new IllegalArgumentException("Unhandled file handle type: " + fileHandle.getClass().getName());
		}

		createDBOFromDTO(dbo, fileHandle);
		if (fileHandle instanceof HasPreviewId) {
			createDBOFromDTO(dbo, (HasPreviewId) fileHandle);
		}
		if (fileHandle instanceof S3FileHandleInterface) {
			createDBOFromDTO(dbo, (S3FileHandleInterface) fileHandle);
		}

		return dbo;
	}

	private static void createDBOFromDTO(DBOFileHandle dbo, FileHandle fileHandle) {
		dbo.setEtag(fileHandle.getEtag());
		if (fileHandle.getCreatedBy() != null) {
			dbo.setCreatedBy(Long.parseLong(fileHandle.getCreatedBy()));
		}
		if (fileHandle.getId() != null) {
			dbo.setId(Long.parseLong(fileHandle.getId()));
		}
		if (fileHandle.getCreatedOn() != null) {
			dbo.setCreatedOn(new Timestamp(fileHandle.getCreatedOn().getTime()));
		}
		dbo.setName(fileHandle.getFileName());
		dbo.setStorageLocationId(fileHandle.getStorageLocationId());
		dbo.setContentType(fileHandle.getContentType());
		dbo.setContentMD5(fileHandle.getContentMd5());
	}

	private static void createDBOFromDTO(DBOFileHandle dbo, HasPreviewId fileHandle) {
		if (fileHandle.getPreviewId() != null) {
			dbo.setPreviewId(Long.parseLong(fileHandle.getPreviewId()));
		}
	}

	private static void createDBOFromDTO(DBOFileHandle dbo, ExternalFileHandle fileHandle) {
		// Validate the URL
		ValidateArgument.validUrl(fileHandle.getExternalURL());
		dbo.setKey(fileHandle.getExternalURL());
	}

	private static void createDBOFromDTO(DBOFileHandle dbo, S3FileHandleInterface fileHandle) {
		dbo.setBucketName(fileHandle.getBucketName());
		dbo.setKey(fileHandle.getKey());
		dbo.setContentSize(fileHandle.getContentSize());
	}

	/**
	 * Create a DTO from the DBO.
	 * 
	 * @param dbo
	 * @return
	 */
	public static FileHandle createDTOFromDBO(DBOFileHandle dbo) {
		FileHandle fileHandle;
		// First determine the type and create the correct type
		switch (dbo.getMetadataTypeEnum()) {
		case EXTERNAL:
			// External
			fileHandle = new ExternalFileHandle();
			break;
		case S3:
			// S3 file
			fileHandle = new S3FileHandle();
			break;
		case PREVIEW:
			// preview
			fileHandle = new PreviewFileHandle();
			break;
		default:
			throw new IllegalArgumentException("Must be External, S3 or Preview but was: " + dbo.getMetadataTypeEnum());
		}

		// now fill in the information
		createDTOFromDBO(fileHandle, dbo);
		if (fileHandle instanceof HasPreviewId) {
			createDTOFromDBO((HasPreviewId) fileHandle, dbo);
		}
		if (fileHandle instanceof ExternalFileHandle) {
			createDTOFromDBO((ExternalFileHandle) fileHandle, dbo);
		}
		if (fileHandle instanceof S3FileHandleInterface) {
			createDTOFromDBO((S3FileHandleInterface) fileHandle, dbo);
		}
		return fileHandle;
	}

	private static void createDTOFromDBO(FileHandle fileHandle, DBOFileHandle dbo) {
		if (dbo.getCreatedBy() != null) {
			fileHandle.setCreatedBy(dbo.getCreatedBy().toString());
		}
		fileHandle.setCreatedOn(dbo.getCreatedOn());
		if (dbo.getId() != null) {
			fileHandle.setId(dbo.getId().toString());
		}
		fileHandle.setEtag(dbo.getEtag());
		fileHandle.setStorageLocationId(dbo.getStorageLocationId());
		fileHandle.setContentType(dbo.getContentType());
		fileHandle.setContentMd5(dbo.getContentMD5());
		fileHandle.setFileName(dbo.getName());
	}

	private static void createDTOFromDBO(HasPreviewId fileHandle, DBOFileHandle dbo) {
		if (dbo.getPreviewId() != null) {
			fileHandle.setPreviewId(dbo.getPreviewId().toString());
		}

	}

	private static void createDTOFromDBO(ExternalFileHandle fileHandle, DBOFileHandle dbo) {
		fileHandle.setExternalURL(dbo.getKey());
	}

	private static void createDTOFromDBO(S3FileHandleInterface fileHandle, DBOFileHandle dbo) {
		fileHandle.setBucketName(dbo.getBucketName());
		fileHandle.setKey(dbo.getKey());
		fileHandle.setContentSize(dbo.getContentSize());
	}

	/**
	 * Create a backup copy of a DBO object
	 * @param dbo
	 * @return
	 */
	public static FileHandleBackup createBackupFromDBO(DBOFileHandle in){
		FileHandleBackup out = new FileHandleBackup();
		if(in.getBucketName() != null){
			out.setBucketName(in.getBucketName());
		}
		if(in.getContentMD5() != null){
			out.setContentMD5(in.getContentMD5());
		}
		if(in.getContentSize() != null){
			out.setContentSize(in.getContentSize());
		}
		if(in.getContentType() != null){
			out.setContentType(in.getContentType());
		}
		if(in.getCreatedBy() != null){
			out.setCreatedBy(in.getCreatedBy());
		}
		if(in.getCreatedOn() != null){
			out.setCreatedOn(in.getCreatedOn().getTime());
		}
		if(in.getEtag() != null){
			out.setEtag(in.getEtag());
		}
		if(in.getId() != null){
			out.setId(in.getId());
		}
		if(in.getKey() != null){
			out.setKey(in.getKey());
		}
		if (in.getMetadataTypeEnum() != null) {
			out.setMetadataType(in.getMetadataTypeEnum().name());
		}
		if(in.getName() != null){
			out.setName(in.getName());
		}
		if(in.getPreviewId() != null){
			out.setPreviewId(in.getPreviewId());
		}
		if (in.getStorageLocationId() != null) {
			out.setStorageLocationId(in.getStorageLocationId());
		}
		return out;
	}
	
	/**
	 * Create a DTO from a backup object.
	 * @param backup
	 * @return
	 */
	public static DBOFileHandle createDBOFromBackup(FileHandleBackup in){
		DBOFileHandle out = new DBOFileHandle();
		if(in.getBucketName() != null){
			out.setBucketName(in.getBucketName());
		}
		if(in.getContentMD5() != null){
			out.setContentMD5(in.getContentMD5());
		}
		if(in.getContentSize() != null){
			out.setContentSize(in.getContentSize());
		}
		if(in.getContentType() != null){
			out.setContentType(in.getContentType());
		}
		if(in.getCreatedBy() != null){
			out.setCreatedBy(in.getCreatedBy());
		}
		if(in.getCreatedOn() != null){
			out.setCreatedOn(new Timestamp(in.getCreatedOn()));
		}
		if(in.getEtag() != null){
			out.setEtag(in.getEtag());
		}
		if(in.getId() != null){
			out.setId(in.getId());
		}
		if(in.getKey() != null){
			out.setKey(in.getKey());
		}
		if(in.getMetadataType() != null){
			out.setMetadataType(MetadataType.valueOf(in.getMetadataType()));
		}
		if(in.getName() != null){
			out.setName(in.getName());
		}
		if(in.getPreviewId() != null){
			out.setPreviewId(in.getPreviewId());
		}
		if (in.getStorageLocationId() != null) {
			out.setStorageLocationId(in.getStorageLocationId());
		}
		return out;
	}
	
	/**
	 * DBOFileHandleAssociation to FileHandleAssociation
	 * @param dbo
	 * @return
	 */
	public static FileHandleAssociation createDTOFromDBO(DBOFileHandleAssociation dbo){
		FileHandleAssociation dto = new FileHandleAssociation();
		if(dbo.getFileHandleId() != null){
			dto.setFileHandleId(dbo.getFileHandleId().toString());
		}
		if(dbo.getAssociatedObjectId() != null){
			dto.setAssociatedObjectId(dbo.getAssociatedObjectId().toString());
		}
		dto.setAssociatedObjectType(dbo.getAssociatedObjectType());
		return dto;
	}
	
	/**
	 * List<DBOFileHandleAssociation> to List<FileHandleAssociation>
	 * @param dbos
	 * @return
	 */
	public static List<FileHandleAssociation> createDTOFromDBO(List<DBOFileHandleAssociation> dbos){
		List<FileHandleAssociation> list = new ArrayList<FileHandleAssociation>(dbos.size());
		for(DBOFileHandleAssociation dto: dbos){
			list.add(createDTOFromDBO(dto));
		}
		return list;
	}
	
	/**
	 * FileHandleAssociation to DBOFileHandleAssociation
	 * @param dto
	 * @return
	 */
	public static DBOFileHandleAssociation createDBOFromDTO(FileHandleAssociation dto){
		DBOFileHandleAssociation dbo = new DBOFileHandleAssociation();
		dbo.setFileHandleId(Long.parseLong(dto.getFileHandleId()));
		dbo.setAssociatedObjectId(Long.parseLong(dto.getAssociatedObjectId()));
		dbo.setAssociatedObjectType(dto.getAssociatedObjectType());
		return dbo;
	}
	
	/**
	 * List<FileHandleAssociation> to List<DBOFileHandleAssociation>
	 * @param dtos
	 * @return
	 */
	public static List<DBOFileHandleAssociation> createDBOFromDTO(List<FileHandleAssociation> dtos){
		List<DBOFileHandleAssociation> list = new ArrayList<DBOFileHandleAssociation>(dtos.size());
		for(FileHandleAssociation dto: dtos){
			list.add(createDBOFromDTO(dto));
		}
		return list;
	}

}
