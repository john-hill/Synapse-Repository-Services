package org.sagebionetworks.repo.model.dbo.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sagebionetworks.repo.model.ACCESS_TYPE;
import org.sagebionetworks.repo.model.AccessControlList;
import org.sagebionetworks.repo.model.AccessRequirement;
import org.sagebionetworks.repo.model.AuthorizationConstants.BOOTSTRAP_PRINCIPAL;
import org.sagebionetworks.repo.model.ManagedACTAccessRequirement;
import org.sagebionetworks.repo.model.ObjectType;
import org.sagebionetworks.repo.model.ResourceAccess;
import org.sagebionetworks.repo.model.RestrictableObjectDescriptor;
import org.sagebionetworks.repo.model.RestrictableObjectType;
import org.sagebionetworks.repo.model.TermsOfUseAccessRequirement;
import org.sagebionetworks.repo.model.dbo.persistence.DBOAccessRequirement;
import org.sagebionetworks.repo.model.dbo.persistence.DBOAccessRequirementRevision;
import org.sagebionetworks.repo.model.dbo.persistence.DBOSubjectAccessRequirement;

import com.google.common.collect.Lists;

public class AccessRequirementUtilsTest {

	public static RestrictableObjectDescriptor createRestrictableObjectDescriptor(String id) {
		return createRestrictableObjectDescriptor(id, RestrictableObjectType.ENTITY);
	}

	public static RestrictableObjectDescriptor createRestrictableObjectDescriptor(String id, RestrictableObjectType type) {
		RestrictableObjectDescriptor rod = new RestrictableObjectDescriptor();
		rod.setId(id);
		rod.setType(type);
		return rod;
	}

	private static AccessRequirement createDTO() {
		TermsOfUseAccessRequirement dto = new TermsOfUseAccessRequirement();
		dto.setId(101L);
		dto.setName("someName");
		dto.setEtag("0");
		dto.setSubjectIds(Lists.newArrayList(createRestrictableObjectDescriptor("syn999")));
		dto.setCreatedBy("555");
		dto.setCreatedOn(new Date());
		dto.setModifiedBy("666");
		dto.setModifiedOn(new Date());
		dto.setConcreteType(TermsOfUseAccessRequirement.class.getName());
		dto.setAccessType(ACCESS_TYPE.DOWNLOAD);
		dto.setTermsOfUse("foo");
		dto.setVersionNumber(1L);
		return dto;
	}

	@Test
	public void testRoundtrip() throws Exception {
		AccessRequirement dto = createDTO();
		RestrictableObjectDescriptor rod = dto.getSubjectIds().get(0);
		// add a duplicate
		dto.getSubjectIds().add(rod);
			
		DBOAccessRequirement dboRequirement = new DBOAccessRequirement();
		DBOAccessRequirementRevision dboRevision = new DBOAccessRequirementRevision();
		AccessRequirementUtils.copyDtoToDbo(dto, dboRequirement, dboRevision);
		assertFalse(dboRequirement.getIsTwoFaRequired());
		AccessRequirement dto2 = AccessRequirementUtils.copyDboToDto(dboRequirement, dboRevision);
		assertEquals(dto, dto2);
		assertEquals(1, dto.getSubjectIds().size());
	}
	
	@Test
	public void testRoundtripWithManagedAR() throws Exception {
		ManagedACTAccessRequirement dto = new ManagedACTAccessRequirement();
		dto.setId(101L);
		dto.setName("someName");
		dto.setEtag("0");
		dto.setSubjectIds(Lists.newArrayList(createRestrictableObjectDescriptor("syn999")));
		dto.setCreatedBy("555");
		dto.setCreatedOn(new Date());
		dto.setModifiedBy("666");
		dto.setModifiedOn(new Date());
		dto.setConcreteType(ManagedACTAccessRequirement.class.getName());
		dto.setAccessType(ACCESS_TYPE.DOWNLOAD);
		dto.setVersionNumber(1L);
		dto.setIsTwoFaRequired(true);
			
		DBOAccessRequirement dboRequirement = new DBOAccessRequirement();
		DBOAccessRequirementRevision dboRevision = new DBOAccessRequirementRevision();
		AccessRequirementUtils.copyDtoToDbo(dto, dboRequirement, dboRevision);
		assertTrue(dboRequirement.getIsTwoFaRequired());
		AccessRequirement dto2 = AccessRequirementUtils.copyDboToDto(dboRequirement, dboRevision);
		assertEquals(dto, dto2);
		assertEquals(1, dto.getSubjectIds().size());
	}

	@Test
	public void testCreateBatchDBOSubjectAccessRequirementWithNullAccessRequirementId() {
		assertThrows(IllegalArgumentException.class, () -> {
			// Call under test
			AccessRequirementUtils.createBatchDBOSubjectAccessRequirement(null, new LinkedList<RestrictableObjectDescriptor>());
		});
	}

	@Test
	public void testCreateBatchDBOSubjectAccessRequirementWithNullRodList() {
		assertThrows(IllegalArgumentException.class, () -> {
			// Call under test
			AccessRequirementUtils.createBatchDBOSubjectAccessRequirement(0L, null);
		});
	}

	@Test
	public void testCreateBatchDBOSubjectAccessRequirementWithEmptyRodList() {
		assertTrue(AccessRequirementUtils.createBatchDBOSubjectAccessRequirement(0L, new LinkedList<RestrictableObjectDescriptor>()).isEmpty());
	}

	@Test
	public void testCopyDBOSubjectsToDTOSubjectsWithNullList() {
		assertThrows(IllegalArgumentException.class, () -> {
			// Call under test
			AccessRequirementUtils.copyDBOSubjectsToDTOSubjects(null);
		});
	}

	@Test
	public void testCopyDBOSubjectsToDTOSubjectsWithEmptyList() {
		assertTrue(AccessRequirementUtils.copyDBOSubjectsToDTOSubjects(new LinkedList<DBOSubjectAccessRequirement>()).isEmpty());
	}

	@Test
	public void testSubjectAccessRequirementRoundTrip() {
		RestrictableObjectDescriptor rod1 = createRestrictableObjectDescriptor("syn1", RestrictableObjectType.ENTITY);
		RestrictableObjectDescriptor rod2 = createRestrictableObjectDescriptor("2", RestrictableObjectType.TEAM);
		Long requirementId = 3L;
		List<DBOSubjectAccessRequirement> dbos = AccessRequirementUtils.createBatchDBOSubjectAccessRequirement(requirementId, Arrays.asList(rod1, rod2));
		assertNotNull(dbos);
		assertEquals(2, dbos.size());
		assertEquals(requirementId, dbos.get(0).getAccessRequirementId());
		assertEquals(requirementId, dbos.get(1).getAccessRequirementId());
		List<RestrictableObjectDescriptor> rodList = AccessRequirementUtils.copyDBOSubjectsToDTOSubjects(dbos);
		assertNotNull(rodList);
		assertEquals(2, rodList.size());
		assertTrue(rodList.contains(rod1));
		assertTrue(rodList.contains(rod2));
	}
	
	@Test
	public void testValidateFieldsValid(){
		AccessRequirement dto = createDTO();
		AccessRequirementUtils.validateFields(dto);
	}
	
	@Test
	public void testValidateFieldsNull(){
		assertThrows(IllegalArgumentException.class, () -> {
			// Call under test
			AccessRequirementUtils.validateFields(null);
		});
	}
	
	@Test
	public void testValidateFieldsNullAccessType(){
		AccessRequirement dto = createDTO();
		dto.setAccessType(null);
		
		assertThrows(IllegalArgumentException.class, () -> {
			// Call under test
			AccessRequirementUtils.validateFields(dto);
		});
	}
	
	@Test
	public void testValidateFieldsNullConcreteType(){
		AccessRequirement dto = createDTO();
		dto.setConcreteType(null);
		
		assertThrows(IllegalArgumentException.class, () -> {
			// Call under test
			AccessRequirementUtils.validateFields(dto);
		});
	}
	
	@Test
	public void testValidateFieldsWrongConcreteType(){
		AccessRequirement dto = createDTO();
		dto.setConcreteType("not.correct");
		
		assertThrows(IllegalArgumentException.class, () -> {
			// Call under test
			AccessRequirementUtils.validateFields(dto);
		});
	}
	
	@Test
	public void testValidateFieldsNullCreatedBy(){
		AccessRequirement dto = createDTO();
		dto.setCreatedBy(null);
		
		assertThrows(IllegalArgumentException.class, () -> {
			// Call under test
			AccessRequirementUtils.validateFields(dto);
		});
	}
	
	@Test
	public void testValidateFieldsNullCreatedOn(){
		AccessRequirement dto = createDTO();
		dto.setCreatedOn(null);
		
		assertThrows(IllegalArgumentException.class, () -> {
			// Call under test
			AccessRequirementUtils.validateFields(dto);
		});
	}
	
	@Test
	public void testValidateFieldsNullEtag(){
		AccessRequirement dto = createDTO();
		dto.setEtag(null);
		
		assertThrows(IllegalArgumentException.class, () -> {
			// Call under test
			AccessRequirementUtils.validateFields(dto);
		});
	}
	
	@Test
	public void testValidateFieldsNullId(){
		AccessRequirement dto = createDTO();
		dto.setId(null);
		
		assertThrows(IllegalArgumentException.class, () -> {
			// Call under test
			AccessRequirementUtils.validateFields(dto);
		});
	}
	
	@Test
	public void testValidateFieldsNullModifiedBy(){
		AccessRequirement dto = createDTO();
		dto.setModifiedBy(null);
		
		assertThrows(IllegalArgumentException.class, () -> {
			// Call under test
			AccessRequirementUtils.validateFields(dto);
		});
	}
	
	@Test
	public void testValidateFieldsNullModifiedOn(){
		AccessRequirement dto = createDTO();
		dto.setModifiedOn(null);
		
		assertThrows(IllegalArgumentException.class, () -> {
			// Call under test
			AccessRequirementUtils.validateFields(dto);
		});
	}
	
	@Test
	public void testValidateFieldsNullVersionNumber(){
		AccessRequirement dto = createDTO();
		dto.setVersionNumber(null);
		
		assertThrows(IllegalArgumentException.class, () -> {
			// Call under test
			AccessRequirementUtils.validateFields(dto);
		});
	}
	
	@Test
	public void testGetUniqueRestrictableObjectDescriptor(){
		List<RestrictableObjectDescriptor> start = Lists.newArrayList(
				createRestrictableObjectDescriptor("syn999"),
				createRestrictableObjectDescriptor("syn111"),
				createRestrictableObjectDescriptor("syn111")
				);
		List<RestrictableObjectDescriptor> expected = Lists.newArrayList(
				createRestrictableObjectDescriptor("syn999"),
				createRestrictableObjectDescriptor("syn111")
				);
		List<RestrictableObjectDescriptor> result = AccessRequirementUtils.getUniqueRestrictableObjectDescriptor(start);
		assertEquals(expected, result);
	}
	
	@Test
	public void testGetUniqueRestrictableObjectDescriptorNull(){
		List<RestrictableObjectDescriptor> start = null;
		List<RestrictableObjectDescriptor> result = AccessRequirementUtils.getUniqueRestrictableObjectDescriptor(start);
		assertEquals(null, result);
	}
	
	@Test
	public void testExtractAllFileHandleIdsWithNoFileHandle() {
		AccessRequirement dto = new ManagedACTAccessRequirement();
		
		Set<String> expected = Collections.emptySet();
		
		// Call under test
		Set<String> result = AccessRequirementUtils.extractAllFileHandleIds(dto);
		
		assertEquals(expected, result);
	}
	
	@Test
	public void testExtractAllFileHandleIdsWithFileHandle() {
		AccessRequirement dto = new ManagedACTAccessRequirement().setDucTemplateFileHandleId("123");
		
		Set<String> expected = Collections.singleton("123");
		
		// Call under test
		Set<String> result = AccessRequirementUtils.extractAllFileHandleIds(dto);
		
		assertEquals(expected, result);
	}
	
	@Test
	public void testExtractAllFileHandleIdsWithNotManaged() {
		AccessRequirement dto = createDTO();
		
		Set<String> expected = Collections.emptySet();
		
		// Call under test
		Set<String> result = AccessRequirementUtils.extractAllFileHandleIds(dto);
		
		assertEquals(expected, result);
	}
	
	@Test
	public void testValidateAccessRequirementAclAccess() {
		AccessControlList acl = new AccessControlList().setResourceAccess(Set.of(
			new ResourceAccess().setPrincipalId(1L).setAccessType(Set.of(ACCESS_TYPE.REVIEW_SUBMISSIONS)),
			new ResourceAccess().setPrincipalId(2L).setAccessType(Set.of(ACCESS_TYPE.REVIEW_SUBMISSIONS)),
		    new ResourceAccess().setPrincipalId(2L).setAccessType(Set.of(ACCESS_TYPE.EXEMPTION_ELIGIBLE))
		));
		
		// Call under test
		AccessRequirementUtils.validateAccessRequirementAcl(acl);
	}
	
	@Test
	public void testValidateAccessRequirementAclAccessWithNullInput() {
		AccessControlList acl = null;
		
		String message = assertThrows(IllegalArgumentException.class, () -> {			
			// Call under test
			AccessRequirementUtils.validateAccessRequirementAcl(acl);
		}).getMessage();
		
		assertEquals("acl is required.", message);
	}
	
	@Test
	public void testValidateAccessRequirementAclAccessWithNullResourceAccess() {
		AccessControlList acl = new AccessControlList().setResourceAccess(null);
		
		String message = assertThrows(IllegalArgumentException.class, () -> {			
			// Call under test
			AccessRequirementUtils.validateAccessRequirementAcl(acl);
		}).getMessage();
		
		assertEquals("acl.resourceAccess is required and must not be empty.", message);
	}
	
	@Test
	public void testValidateAccessRequirementAclAccessWithEmptyResourceAccess() {
		AccessControlList acl = new AccessControlList().setResourceAccess(Collections.emptySet());
		
		String message = assertThrows(IllegalArgumentException.class, () -> {			
			// Call under test
			AccessRequirementUtils.validateAccessRequirementAcl(acl);
		}).getMessage();
		
		assertEquals("acl.resourceAccess is required and must not be empty.", message);
	}
	
	@Test
	public void testValidateAccessRequirementAclAccessWithMultipleAccessTypes() {
		AccessControlList acl = new AccessControlList().setResourceAccess(Set.of(
			new ResourceAccess().setPrincipalId(1L).setAccessType(Set.of(ACCESS_TYPE.REVIEW_SUBMISSIONS, ACCESS_TYPE.READ))
		));
		
		String message = assertThrows(IllegalArgumentException.class, () -> {			
			// Call under test
			AccessRequirementUtils.validateResourceAccessOfAclForOwnerType(acl, ObjectType.ACCESS_REQUIREMENT);
		}).getMessage();
		
		assertEquals("The access type READ is not allowed for ACCESS_REQUIREMENT.", message);
	}
	
	@Test
	public void testValidateAccessRequirementAclAccessWithWrongAccessTypes() {
		AccessControlList acl = new AccessControlList().setResourceAccess(Set.of(
			new ResourceAccess().setPrincipalId(1L).setAccessType(Set.of(ACCESS_TYPE.DOWNLOAD))
		));
		
		String message = assertThrows(IllegalArgumentException.class, () -> {			
			// Call under test
			AccessRequirementUtils.validateResourceAccessOfAclForOwnerType(acl, ObjectType.ACCESS_REQUIREMENT);
		}).getMessage();
		
		assertEquals("The access type DOWNLOAD is not allowed for ACCESS_REQUIREMENT.", message);
	}
	
	@Test
	public void testValidateAccessRequirementAclAccessWithWrongAnonymousUser() {
		AccessControlList acl = new AccessControlList().setResourceAccess(Set.of(
			new ResourceAccess().setPrincipalId(BOOTSTRAP_PRINCIPAL.ANONYMOUS_USER.getPrincipalId()).setAccessType(Set.of(ACCESS_TYPE.READ))
		));
		
		String message = assertThrows(IllegalArgumentException.class, () -> {			
			// Call under test
			AccessRequirementUtils.validateAccessRequirementAcl(acl);
		}).getMessage();
		
		assertEquals("Cannot assign permissions to the anonmous user.", message);
	}
	
	@Test
	public void testValidateAccessRequirementAclAccessWithWrongPublicGroup() {
		AccessControlList acl = new AccessControlList().setResourceAccess(Set.of(
			new ResourceAccess().setPrincipalId(BOOTSTRAP_PRINCIPAL.PUBLIC_GROUP.getPrincipalId()).setAccessType(Set.of(ACCESS_TYPE.READ))
		));
		
		String message = assertThrows(IllegalArgumentException.class, () -> {			
			// Call under test
			AccessRequirementUtils.validateAccessRequirementAcl(acl);
		}).getMessage();
		
		assertEquals("Cannot assign permissions to the public group.", message);
	}

	@Test
	public void testValidateAccessRequirementAclAccessForEntityOwnerType() {
		AccessControlList acl = new AccessControlList().setResourceAccess(Set.of(
				new ResourceAccess().setPrincipalId(1L).setAccessType(new HashSet<>(Arrays.asList(ACCESS_TYPE.CREATE, ACCESS_TYPE.DOWNLOAD, ACCESS_TYPE.READ,
						ACCESS_TYPE.CHANGE_PERMISSIONS, ACCESS_TYPE.CHANGE_SETTINGS, ACCESS_TYPE.DELETE, ACCESS_TYPE.MODERATE,
						ACCESS_TYPE.UPDATE, ACCESS_TYPE.SEND_MESSAGE, ACCESS_TYPE.TEAM_MEMBERSHIP_UPDATE, ACCESS_TYPE.DELETE_SUBMISSION,
						ACCESS_TYPE.PARTICIPATE, ACCESS_TYPE.READ_PRIVATE_SUBMISSION, ACCESS_TYPE.SUBMIT, ACCESS_TYPE.UPDATE_SUBMISSION,
						ACCESS_TYPE.UPLOAD))),
				new ResourceAccess().setPrincipalId(2L).setAccessType(Set.of(ACCESS_TYPE.SUBMIT))
		));

		AccessRequirementUtils.validateResourceAccessOfAclForOwnerType(acl, ObjectType.ENTITY);
	}

	@Test
	public void testValidateAccessRequirementAclAccessForEvaluationOwnerType() {
		AccessControlList acl = new AccessControlList().setResourceAccess(Set.of(
				new ResourceAccess().setPrincipalId(1L).setAccessType(new HashSet<>(Arrays.asList(ACCESS_TYPE.READ, ACCESS_TYPE.CHANGE_PERMISSIONS, ACCESS_TYPE.CREATE,
						ACCESS_TYPE.DELETE, ACCESS_TYPE.DELETE_SUBMISSION, ACCESS_TYPE.READ_PRIVATE_SUBMISSION, ACCESS_TYPE.SUBMIT,
						ACCESS_TYPE.UPDATE, ACCESS_TYPE.UPDATE_SUBMISSION, ACCESS_TYPE.PARTICIPATE, ACCESS_TYPE.CHANGE_SETTINGS,
						ACCESS_TYPE.DOWNLOAD, ACCESS_TYPE.MODERATE))),
				new ResourceAccess().setPrincipalId(2L).setAccessType(Set.of(ACCESS_TYPE.MODERATE))
		));

		AccessRequirementUtils.validateResourceAccessOfAclForOwnerType(acl, ObjectType.EVALUATION);
	}

	@Test
	public void testValidateAccessRequirementAclAccessForTeamOwnerType() {
		AccessControlList acl = new AccessControlList().setResourceAccess(Set.of(
				new ResourceAccess().setPrincipalId(1L).setAccessType(new HashSet<>(Arrays.asList(ACCESS_TYPE.DELETE, ACCESS_TYPE.READ, ACCESS_TYPE.SEND_MESSAGE,
						ACCESS_TYPE.TEAM_MEMBERSHIP_UPDATE, ACCESS_TYPE.UPDATE, ACCESS_TYPE.CREATE, ACCESS_TYPE.DOWNLOAD,
						ACCESS_TYPE.CHANGE_PERMISSIONS, ACCESS_TYPE.CHANGE_SETTINGS, ACCESS_TYPE.MODERATE, ACCESS_TYPE.DELETE_SUBMISSION,
						ACCESS_TYPE.SUBMIT, ACCESS_TYPE.UPDATE_SUBMISSION, ACCESS_TYPE.PARTICIPATE))),
				new ResourceAccess().setPrincipalId(2L).setAccessType(Set.of(ACCESS_TYPE.READ))
		));

		AccessRequirementUtils.validateResourceAccessOfAclForOwnerType(acl, ObjectType.TEAM);
	}

	@Test
	public void testValidateAccessRequirementAclAccessForFormGroupOwnerType() {
		AccessControlList acl = new AccessControlList().setResourceAccess(Set.of(
				new ResourceAccess().setPrincipalId(1L).setAccessType(new HashSet<>(Arrays.asList(ACCESS_TYPE.CHANGE_PERMISSIONS,
						ACCESS_TYPE.READ, ACCESS_TYPE.READ_PRIVATE_SUBMISSION, ACCESS_TYPE.SUBMIT))),
				new ResourceAccess().setPrincipalId(2L).setAccessType(Set.of(ACCESS_TYPE.READ_PRIVATE_SUBMISSION))
		));

		AccessRequirementUtils.validateResourceAccessOfAclForOwnerType(acl, ObjectType.FORM_GROUP);
	}

	@Test
	public void testValidateAccessRequirementAclAccessForOrganizationOwnerType() {
		AccessControlList acl = new AccessControlList().setResourceAccess(Set.of(
				new ResourceAccess().setPrincipalId(1L).setAccessType(new HashSet<>(Arrays.asList(ACCESS_TYPE.CHANGE_PERMISSIONS,
						ACCESS_TYPE.CREATE, ACCESS_TYPE.DELETE, ACCESS_TYPE.READ, ACCESS_TYPE.UPDATE))),
				new ResourceAccess().setPrincipalId(2L).setAccessType(Set.of(ACCESS_TYPE.CREATE))
		));

		AccessRequirementUtils.validateResourceAccessOfAclForOwnerType(acl, ObjectType.ORGANIZATION);
	}

	@Test
	public void testValidateAccessRequirementInvalidAclAccessForEntityOwnerType() {
		AccessControlList acl = new AccessControlList().setResourceAccess(Set.of(
				new ResourceAccess().setPrincipalId(1L).setAccessType(Set.of(ACCESS_TYPE.DELETE,ACCESS_TYPE.UPDATE,ACCESS_TYPE.DOWNLOAD)),
				new ResourceAccess().setPrincipalId(2L).setAccessType(Set.of(ACCESS_TYPE.READ, ACCESS_TYPE.EXEMPTION_ELIGIBLE))
		));

		String message = assertThrows(IllegalArgumentException.class, () -> {
			// Call under test
			AccessRequirementUtils.validateResourceAccessOfAclForOwnerType(acl, ObjectType.ENTITY);
		}).getMessage();

		assertEquals("The access type EXEMPTION_ELIGIBLE is not allowed for ENTITY.", message);
	}

	@Test
	public void testValidateAccessRequirementInvalidAclAccessForEvaluationOwnerType() {
		AccessControlList acl = new AccessControlList().setResourceAccess(Set.of(
				new ResourceAccess().setPrincipalId(1L).setAccessType(Set.of(ACCESS_TYPE.DELETE,ACCESS_TYPE.UPDATE,ACCESS_TYPE.DOWNLOAD)),
				new ResourceAccess().setPrincipalId(2L).setAccessType(Set.of(ACCESS_TYPE.READ, ACCESS_TYPE.UPLOAD))
		));

		String message = assertThrows(IllegalArgumentException.class, () -> {
			// Call under test
			AccessRequirementUtils.validateResourceAccessOfAclForOwnerType(acl, ObjectType.EVALUATION);
		}).getMessage();

		assertEquals("The access type UPLOAD is not allowed for EVALUATION.", message);
	}

	@Test
	public void testValidateAccessRequirementInvalidAclAccessForTeamOwnerType() {
		AccessControlList acl = new AccessControlList().setResourceAccess(Set.of(
				new ResourceAccess().setPrincipalId(1L).setAccessType(Set.of(ACCESS_TYPE.DELETE,ACCESS_TYPE.UPDATE,ACCESS_TYPE.DOWNLOAD)),
				new ResourceAccess().setPrincipalId(2L).setAccessType(Set.of(ACCESS_TYPE.READ_PRIVATE_SUBMISSION))
		));

		String message = assertThrows(IllegalArgumentException.class, () -> {
			// Call under test
			AccessRequirementUtils.validateResourceAccessOfAclForOwnerType(acl, ObjectType.TEAM);
		}).getMessage();

		assertEquals("The access type READ_PRIVATE_SUBMISSION is not allowed for TEAM.", message);
	}

	@Test
	public void testValidateAccessRequirementInvalidAclAccessForFormGroupOwnerType() {
		AccessControlList acl = new AccessControlList().setResourceAccess(Set.of(
				new ResourceAccess().setPrincipalId(1L).setAccessType(Set.of(ACCESS_TYPE.DELETE,ACCESS_TYPE.READ,ACCESS_TYPE.READ_PRIVATE_SUBMISSION)),
				new ResourceAccess().setPrincipalId(2L).setAccessType(Set.of(ACCESS_TYPE.READ_PRIVATE_SUBMISSION))
		));

		String message = assertThrows(IllegalArgumentException.class, () -> {
			// Call under test
			AccessRequirementUtils.validateResourceAccessOfAclForOwnerType(acl, ObjectType.FORM_GROUP);
		}).getMessage();

		assertEquals("The access type DELETE is not allowed for FORM_GROUP.", message);
	}

	@Test
	public void testValidateAccessRequirementInvalidAclAccessForOrganizationOwnerType() {
		AccessControlList acl = new AccessControlList().setResourceAccess(Set.of(
				new ResourceAccess().setPrincipalId(1L).setAccessType(Set.of(ACCESS_TYPE.DELETE,ACCESS_TYPE.READ,ACCESS_TYPE.UPLOAD)),
				new ResourceAccess().setPrincipalId(2L).setAccessType(Set.of(ACCESS_TYPE.READ))
		));

		String message = assertThrows(IllegalArgumentException.class, () -> {
			// Call under test
			AccessRequirementUtils.validateResourceAccessOfAclForOwnerType(acl, ObjectType.ORGANIZATION);
		}).getMessage();

		assertEquals("The access type UPLOAD is not allowed for ORGANIZATION.", message);
	}
}
