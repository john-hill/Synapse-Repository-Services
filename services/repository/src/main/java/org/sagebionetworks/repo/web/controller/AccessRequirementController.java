package org.sagebionetworks.repo.web.controller;

import static org.sagebionetworks.repo.web.UrlHelpers.EVALUATION_ID_PATH_VAR_WITHOUT_BRACKETS;
import static org.sagebionetworks.repo.web.UrlHelpers.ID_PATH_VARIABLE;

import javax.servlet.http.HttpServletRequest;

import org.sagebionetworks.repo.model.AccessRequirement;
import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.DatastoreException;
import org.sagebionetworks.repo.model.PaginatedResults;
import org.sagebionetworks.repo.model.RestrictableObjectDescriptor;
import org.sagebionetworks.repo.model.RestrictableObjectType;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.web.ForbiddenException;
import org.sagebionetworks.repo.web.NotFoundException;
import org.sagebionetworks.repo.web.UrlHelpers;
import org.sagebionetworks.repo.web.rest.doc.ControllerInfo;
import org.sagebionetworks.repo.web.service.ServiceProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * These services manage the Access Requirements/Restrictions which may be placed on Entities or
 * Evaluation queues.  An Access Requirement specifies the type of access being restricted as well
 * as how the requirement is fulfilled. 
 * 
 * More design information is available 
 * <a href=https://sagebionetworks.jira.com/wiki/display/PLFM/Data+Access+Control/>here.</a>
 * 
 * @author brucehoff
 *
 */
@ControllerInfo(displayName="Access Requirement Services", path="repo/v1")
@Controller
public class AccessRequirementController extends BaseController {

	@Autowired
	ServiceProvider serviceProvider;

	/**
	 * Add an access requirement to an entity or Evaluation queue.  This is a tool for the Synapse Access and Compliance Team.
	 * @param userId
	 * @param header
	 * @param request
	 * @return
	 * @throws Exception
	 */
	@ResponseStatus(HttpStatus.CREATED)
	@RequestMapping(value = UrlHelpers.ACCESS_REQUIREMENT, method = RequestMethod.POST)
	public @ResponseBody
	AccessRequirement createAccessRequirement(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM, required = false) String userId,
			@RequestHeader HttpHeaders header,
			HttpServletRequest request
			) throws Exception {
		AccessRequirement accessRequirement = (AccessRequirement)ControllerEntityClassHelper.deserialize(request, header);
		return serviceProvider.getAccessRequirementService().createAccessRequirement(userId, accessRequirement);
	}
	
	/**
	 * Add a temporary access restriction that prevents access pending review by the Synapse Access and Compliance Team.  
	 * This is a tool for the object's owner.
	 * 
	 * @param userId
	 * @param id
	 * @return
	 * @throws Exception
	 */
	@ResponseStatus(HttpStatus.CREATED)
	@RequestMapping(value = UrlHelpers.ENTITY_LOCK_ACCESS_REQURIEMENT, method = RequestMethod.POST)
	public @ResponseBody
	AccessRequirement createLockAccessRequirement(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM, required = false) String userId,
			@PathVariable String id
			) throws Exception {
		return serviceProvider.getAccessRequirementService().createLockAccessRequirement(userId, id);
	}
	

	/**
	 * Retrieve paginated list of unfulfilled access requirements (of type DOWNLOAD) for an entity.
	 * @param userId
	 * @param entityId
	 * @param request
	 * @return
	 * @throws DatastoreException
	 * @throws UnauthorizedException
	 * @throws NotFoundException
	 */
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = UrlHelpers.ENTITY_ACCESS_REQUIREMENT_UNFULFILLED_WITH_ID, method = RequestMethod.GET)
	public @ResponseBody
	PaginatedResults<AccessRequirement>
	 getUnfulfilledEntityAccessRequirement(
				@RequestParam(value = AuthorizationConstants.USER_ID_PARAM, required = false) String userId,
			@PathVariable(value = ID_PATH_VARIABLE) String entityId,
			HttpServletRequest request
			) throws DatastoreException, UnauthorizedException, NotFoundException {
		RestrictableObjectDescriptor subjectId = new RestrictableObjectDescriptor();
		subjectId.setId(entityId);
		subjectId.setType(RestrictableObjectType.ENTITY);
		return serviceProvider.getAccessRequirementService().getUnfulfilledAccessRequirements(userId, subjectId, request);
	}

	/**
	 * Retrieve paginated list of ALL access requirements associated with an entity.
	 * @param userId
	 * @param entityId
	 * @param request
	 * @return
	 * @throws DatastoreException
	 * @throws UnauthorizedException
	 * @throws NotFoundException
	 */
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = UrlHelpers.ACCESS_REQUIREMENT_WITH_ENTITY_ID, method = RequestMethod.GET)
	public @ResponseBody
	PaginatedResults<AccessRequirement>
	 getEntityAccessRequirements(
				@RequestParam(value = AuthorizationConstants.USER_ID_PARAM, required = false) String userId,
				@PathVariable(value = ID_PATH_VARIABLE) String entityId,
			HttpServletRequest request
			) throws DatastoreException, UnauthorizedException, NotFoundException {
		RestrictableObjectDescriptor subjectId = new RestrictableObjectDescriptor();
		subjectId.setId(entityId);
		subjectId.setType(RestrictableObjectType.ENTITY);
		return serviceProvider.getAccessRequirementService().getAccessRequirements(userId, subjectId, request);
	}
	
	/**
	 * Retrieve a paginated list of unfulfilled access requirements (of type DOWNLOAD or PARTICIPATE) for an evaluation queue.
	 * @param userId
	 * @param evaluationId
	 * @param request
	 * @return
	 * @throws DatastoreException
	 * @throws UnauthorizedException
	 * @throws NotFoundException
	 */
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = UrlHelpers.EVALUATION_ACCESS_REQUIREMENT_UNFULFILLED_WITH_ID, method = RequestMethod.GET)
	public @ResponseBody
	PaginatedResults<AccessRequirement>
	 getUnfulfilledEvaluationAccessRequirement(
				@RequestParam(value = AuthorizationConstants.USER_ID_PARAM, required = false) String userId,
			@PathVariable(value = EVALUATION_ID_PATH_VAR_WITHOUT_BRACKETS) String evaluationId,
			HttpServletRequest request
			) throws DatastoreException, UnauthorizedException, NotFoundException {
		RestrictableObjectDescriptor subjectId = new RestrictableObjectDescriptor();
		subjectId.setId(evaluationId);
		subjectId.setType(RestrictableObjectType.EVALUATION);
		return serviceProvider.getAccessRequirementService().getUnfulfilledAccessRequirements(userId, subjectId, request);
	}

	/**
	 * Retrieve paginated list of ALL access requirements associated with an evaluation queue.
	 * @param userId
	 * @param evaluationId
	 * @param request
	 * @return
	 * @throws DatastoreException
	 * @throws UnauthorizedException
	 * @throws NotFoundException
	 */
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = UrlHelpers.ACCESS_REQUIREMENT_WITH_EVALUATION_ID, method = RequestMethod.GET)
	public @ResponseBody
	PaginatedResults<AccessRequirement>
	 getEvaluationAccessRequirements(
				@RequestParam(value = AuthorizationConstants.USER_ID_PARAM, required = false) String userId,
				@PathVariable(value = EVALUATION_ID_PATH_VAR_WITHOUT_BRACKETS) String evaluationId,
			HttpServletRequest request
			) throws DatastoreException, UnauthorizedException, NotFoundException {
		RestrictableObjectDescriptor subjectId = new RestrictableObjectDescriptor();
		subjectId.setId(evaluationId);
		subjectId.setType(RestrictableObjectType.EVALUATION);
		return serviceProvider.getAccessRequirementService().getAccessRequirements(userId, subjectId, request);
	}

	/**
	 * Delete an access requirement.
	 * @param userId
	 * @param requirementId
	 * @param request
	 * @throws DatastoreException
	 * @throws UnauthorizedException
	 * @throws NotFoundException
	 */
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = UrlHelpers.ACCESS_REQUIREMENT_WITH_REQUIREMENT_ID, method = RequestMethod.DELETE)
	public void deleteAccessRequirements(
				@RequestParam(value = AuthorizationConstants.USER_ID_PARAM, required = false) String userId,
			@PathVariable String requirementId,
			HttpServletRequest request) throws DatastoreException, UnauthorizedException, NotFoundException {
		serviceProvider.getAccessRequirementService().deleteAccessRequirements(userId, requirementId);
	}
}
