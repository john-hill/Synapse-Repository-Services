package org.sagebionetworks.repo.web;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

import org.sagebionetworks.ids.IdGenerator;
import org.sagebionetworks.repo.manager.EntityManager;
import org.sagebionetworks.repo.manager.PermissionsManager;
import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.model.ACCESS_TYPE;
import org.sagebionetworks.repo.model.ACLInheritanceException;
import org.sagebionetworks.repo.model.AccessControlList;
import org.sagebionetworks.repo.model.Annotations;
import org.sagebionetworks.repo.model.ConflictingUpdateException;
import org.sagebionetworks.repo.model.DatastoreException;
import org.sagebionetworks.repo.model.Entity;
import org.sagebionetworks.repo.model.EntityHeader;
import org.sagebionetworks.repo.model.EntityHeaderQueryResults;
import org.sagebionetworks.repo.model.EntityType;
import org.sagebionetworks.repo.model.InvalidModelException;
import org.sagebionetworks.repo.model.NodeQueryDao;
import org.sagebionetworks.repo.model.NodeQueryResults;
import org.sagebionetworks.repo.model.PaginatedResults;
import org.sagebionetworks.repo.model.QueryResults;
import org.sagebionetworks.repo.model.ServiceConstants;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.auth.UserEntityPermissions;
import org.sagebionetworks.repo.model.jdo.KeyFactory;
import org.sagebionetworks.repo.model.query.BasicQuery;
import org.sagebionetworks.repo.web.controller.MetadataProviderFactory;
import org.sagebionetworks.repo.web.controller.metadata.AllTypesValidator;
import org.sagebionetworks.repo.web.controller.metadata.EntityEvent;
import org.sagebionetworks.repo.web.controller.metadata.EventType;
import org.sagebionetworks.repo.web.controller.metadata.TypeSpecificMetadataProvider;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Implementation for REST controller for CRUD operations on Entity DTOs and Entity
 * DAOs
 * <p>
 * 
 * This class performs the basic CRUD operations for all our DAO-backed model
 * objects. See controllers specific to particular models for any special
 * handling.
 * 
 * @author deflaux
 * @param <T>
 *            the particular type of entity the controller is managing
 */
@SuppressWarnings({"rawtypes","unchecked"})
public class GenericEntityControllerImpl implements GenericEntityController {
	
	@Autowired
	NodeQueryDao nodeQueryDao;
	@Autowired
	EntityManager entityManager;
	@Autowired
	PermissionsManager permissionsManager;
	@Autowired
	UserManager userManager;
	@Autowired
	private MetadataProviderFactory metadataProviderFactory;
	@Autowired
	private IdGenerator idGenerator;
	@Autowired
	private AllTypesValidator allTypesValidator;
	
	public GenericEntityControllerImpl(){
		
	}
	

	/**
	 * Provided for tests
	 * @param entitiesAccessor
	 * @param entityManager
	 */
	GenericEntityControllerImpl(EntityManager entityManager) {
		super();
		this.entityManager = entityManager;
	}

	@Override
	public <T extends Entity> PaginatedResults<T> getEntities(String userId, PaginatedParameters paging,
			HttpServletRequest request, Class<? extends T> clazz) throws DatastoreException, NotFoundException, UnauthorizedException {
		ServiceConstants.validatePaginationParams(paging.getOffset(), paging.getLimit());
		UserInfo userInfo = userManager.getUserInfo(userId);
		EntityType type =  EntityType.getNodeTypeForClass(clazz);
		// First build the query that will be used
		BasicQuery query = QueryUtils.createFindPaginagedOfType(paging, type);
		// Execute the query and convert to entities.
		return executeQueryAndConvertToEntites(paging, request, clazz,
				userInfo, query);
	}
	

	@Override
	public <T extends Entity> PaginatedResults<T> getAllVerionsOfEntity(
			String userId, Integer offset, Integer limit, String entityId,
			HttpServletRequest request, Class<? extends T> clazz)
			throws DatastoreException, UnauthorizedException, NotFoundException {
		if(offset == null){
			offset = 1;
		}
		if(limit == null){
			limit = Integer.MAX_VALUE;
		}
		// First get the full list of all revisions numbers
		UserInfo userInfo = userManager.getUserInfo(userId);
		EntityType type =  EntityType.getNodeTypeForClass(clazz);
		List<Long> versionNumbers = entityManager.getAllVersionNumbersForEntity(userInfo, entityId);
		// Now fetch the versions requested
		int start = offset-1;
		int end = Math.min(start+limit, versionNumbers.size());
		List<T> entityList = new ArrayList<T>();
		for(int i=start; i<end; i++){
			long versionNumber = versionNumbers.get(i);
			T entity = (T) getEntityForVersion(userInfo, entityId, versionNumber, request, type.getClassForType());
			entityList.add(entity);
		}
		// Return the paginated results
		return new PaginatedResults<T>(request.getServletPath()
				+ UrlHelpers.ENTITY, entityList,
				versionNumbers.size(), offset, limit, "versionNumber", false);
	}
	

	@Override
	public <T extends Entity> PaginatedResults<T> getAllVerionsOfEntity(
			String userId, Integer offset, Integer limit, String entityId,
			HttpServletRequest request)
			throws DatastoreException, UnauthorizedException, NotFoundException {
		if(offset == null){
			offset = 1;
		}
		if(limit == null){
			limit = Integer.MAX_VALUE;
		}
		// First get the full list of all revisions numbers
		UserInfo userInfo = userManager.getUserInfo(userId);
		EntityType type =  entityManager.getEntityType(userInfo, entityId);
		
		// TODO: Figure out with John how to use the function above instead of dup'ing code
		
		List<Long> versionNumbers = entityManager.getAllVersionNumbersForEntity(userInfo, entityId);
		// Now fetch the versions requested
		int start = offset-1;
		int end = Math.min(start+limit, versionNumbers.size());
		List<T> entityList = new ArrayList<T>();
		for(int i=start; i<end; i++){
			long versionNumber = versionNumbers.get(i);
			T entity = (T) getEntityForVersion(userInfo, entityId, versionNumber, request, type.getClassForType());
			entityList.add(entity);
		}
		// Return the paginated results
		return new PaginatedResults<T>(request.getServletPath()
				+ UrlHelpers.ENTITY, entityList,
				versionNumbers.size(), offset, limit, "versionNumber", false);
	}
	
	/**
	 * First, execute the given query to determine the nodes that match the criteria.
	 * Then, for each node id, fetch the entity and build up the paginated results.
	 * 
	 * @param <T>
	 * @param paging
	 * @param request
	 * @param clazz
	 * @param userInfo
	 * @param nodeResults
	 * @return
	 * @throws NotFoundException
	 * @throws DatastoreException
	 * @throws UnauthorizedException
	 */
	private <T extends Entity> PaginatedResults<T> executeQueryAndConvertToEntites(
			PaginatedParameters paging,
			HttpServletRequest request,
			Class<? extends T> clazz,
			UserInfo userInfo,
			BasicQuery query) throws NotFoundException,
			DatastoreException, UnauthorizedException {
		// First execute the query.
		NodeQueryResults nodeResults = nodeQueryDao.executeQuery(query, userInfo);
		// Fetch each entity
		List<T> entityList = new ArrayList<T>();
		for(String id: nodeResults.getResultIds()){
			T entity = this.getEntity(userInfo, id, request, clazz, EventType.GET);
			entityList.add(entity);
		}
		return new PaginatedResults<T>(request.getServletPath()
				+ UrlHelpers.ENTITY, entityList,
				nodeResults.getTotalNumberOfResults(), paging.getOffset(), paging.getLimit(), paging.getSortBy(), paging.getAscending());
	}

	@Override
	public <T extends Entity> T getEntity(String userId, String id, HttpServletRequest request, Class<? extends T> clazz)
			throws NotFoundException, DatastoreException, UnauthorizedException {
		String entityId = UrlHelpers.getEntityIdFromUriId(id);
		UserInfo userInfo = userManager.getUserInfo(userId);
		return getEntity(userInfo, entityId, request, clazz, EventType.GET);
	}
	
	@Override
	public Entity getEntity(String userId, String id, HttpServletRequest request) throws NotFoundException, DatastoreException, UnauthorizedException {
		UserInfo userInfo = userManager.getUserInfo(userId);
		EntityHeader header = entityManager.getEntityHeader(userInfo, id);
		EntityType type = EntityType.getEntityType(header.getType());
		return getEntity(userInfo, id, request, type.getClassForType(), EventType.GET);
	}
	/**
	 * Any time we fetch an entity we do so through this path.
	 * @param <T>
	 * @param info
	 * @param id
	 * @param request
	 * @param clazz
	 * @return
	 * @throws NotFoundException
	 * @throws DatastoreException
	 * @throws UnauthorizedException
	 */
	public <T extends Entity> T getEntity(UserInfo info, String id, HttpServletRequest request, Class<? extends T> clazz, EventType eventType) throws NotFoundException, DatastoreException, UnauthorizedException{
		// Determine the object type from the url.
		EntityType type = EntityType.getNodeTypeForClass(clazz);
		T entity = entityManager.getEntity(info, id, clazz);
		// Do all of the type specific stuff.
		this.doAddServiceSpecificMetadata(info, entity, type, request, eventType);
		return entity;
	}
	
	/**
	 * Do all type specific stuff to an entity
	 * @param <T>
	 * @param info
	 * @param entity
	 * @param type
	 * @param request
	 * @param eventType
	 * @throws DatastoreException
	 * @throws NotFoundException
	 * @throws UnauthorizedException
	 */
	private <T extends Entity> void doAddServiceSpecificMetadata(UserInfo info, T entity, EntityType type, HttpServletRequest request, EventType eventType) throws DatastoreException, NotFoundException, UnauthorizedException{
		// Fetch the provider that will validate this entity.
		List<TypeSpecificMetadataProvider<Entity>> providers = metadataProviderFactory.getMetadataProvider(type);

		// Add the type specific metadata that is common to all objects.
		addServiceSpecificMetadata(entity, request);
		// Add the type specific metadata
		if(providers != null) {
			for(TypeSpecificMetadataProvider<Entity> provider : providers) {
				provider.addTypeSpecificMetadata(entity, request, info, eventType);
			}
		}
	}
	
	@Override
	public <T extends Entity> T getEntityForVersion(String userId,String id, Long versionNumber, HttpServletRequest request,
			Class<? extends T> clazz) throws NotFoundException,
			DatastoreException, UnauthorizedException {
		UserInfo userInfo = userManager.getUserInfo(userId);
		return getEntityForVersion(userInfo, id, versionNumber, request, clazz);
	}
	
	@Override
	public <T extends Entity> T getEntityForVersion(UserInfo info, String id, Long versionNumber, HttpServletRequest request,
			Class<? extends T> clazz) throws NotFoundException,
			DatastoreException, UnauthorizedException {
		// Determine the object type from the url.
		EntityType type = EntityType.getNodeTypeForClass(clazz);
		T entity = entityManager.getEntityForVersion(info, id, versionNumber, clazz);
		// Do all of the type specific stuff.
		this.doAddServiceSpecificMetadata(info, entity, type, request, EventType.GET);
		return entity;
	}
	
	@Override
	public Entity getEntityForVersion(String userId, String id,	Long versionNumber, HttpServletRequest request)
			throws NotFoundException, DatastoreException, UnauthorizedException {
		UserInfo userInfo = userManager.getUserInfo(userId);
		EntityType type = entityManager.getEntityType(userInfo, id);
		return getEntityForVersion(userId, id, versionNumber, request, type.getClassForType());
	}

	@Override
	public <T extends Entity> T createEntity(String userId, T newEntity, HttpServletRequest request)
			throws DatastoreException, InvalidModelException,
			UnauthorizedException, NotFoundException {
		// Determine the object type from the url.
		Class<? extends T> clazz = (Class<? extends T>) newEntity.getClass();
		EntityType type = EntityType.getNodeTypeForClass(newEntity.getClass());
		// Fetch the provider that will validate this entity.
		// Get the user
		UserInfo userInfo = userManager.getUserInfo(userId);
		// Create a new id for this entity
		long newId = idGenerator.generateNewId();
		newEntity.setId(KeyFactory.keyToString(newId));
		EventType eventType = EventType.CREATE;
		// Fire the event
		fireValidateEvent(userInfo, eventType, newEntity, type);
		String id = entityManager.createEntity(userInfo, newEntity);
		// Return the resulting entity.
		return getEntity(userInfo, id, request, clazz, eventType);
	}
	
	/**
	 * Fire a validate event.  
	 * @param userInfo
	 * @param eventType
	 * @param entity
	 * @param type
	 * @throws NotFoundException
	 * @throws DatastoreException
	 * @throws UnauthorizedException
	 * @throws InvalidModelException
	 */
	public void fireValidateEvent(UserInfo userInfo, EventType eventType, Entity entity, EntityType type) throws NotFoundException, DatastoreException, UnauthorizedException, InvalidModelException{
		List<EntityHeader> newParentPath = null;
		if(entity.getParentId() != null){
			newParentPath = entityManager.getEntityPathAsAdmin(entity.getParentId());
		}
		EntityEvent event = new EntityEvent(eventType, newParentPath, userInfo);
		// First apply validation that is common to all types.
		allTypesValidator.validateEntity(entity, event);
		// Now validate for a specific type.
		List<TypeSpecificMetadataProvider<Entity>> providers = metadataProviderFactory.getMetadataProvider(type);
		// Validate the entity
		if(providers != null) {
			for(TypeSpecificMetadataProvider<Entity> provider : providers) {
				provider.validateEntity(entity, event);
			}
		}
	}
	
	@Override
	public <T extends Entity> T updateEntity(String userId,
			T updatedEntity, boolean newVersion, HttpServletRequest request)
			throws NotFoundException, ConflictingUpdateException,
			DatastoreException, InvalidModelException, UnauthorizedException {
		if(updatedEntity == null) throw new IllegalArgumentException("Entity cannot be null");
		if(updatedEntity.getId() == null) throw new IllegalArgumentException("Updated Entity cannot have a null id");
		// Get the type for this entity.
		EntityType type = EntityType.getNodeTypeForClass(updatedEntity.getClass());
		Class<? extends T> clazz = (Class<? extends T>) updatedEntity.getClass();
		// Fetch the provider that will validate this entity.
		// Get the user
		UserInfo userInfo = userManager.getUserInfo(userId);
		EventType eventType = EventType.UPDATE;
		// Fire the event
		fireValidateEvent(userInfo, eventType, updatedEntity, type);
		// Keep the entity id
		String entityId = updatedEntity.getId();
		// Now do the update
		entityManager.updateEntity(userInfo, updatedEntity, newVersion);
		// Return the udpated entity
		return getEntity(userInfo, entityId, request, clazz, eventType);
	}
	
	@Override
	public void deleteEntity(String userId, String id)
			throws NotFoundException, DatastoreException, UnauthorizedException {

		String entityId = UrlHelpers.getEntityIdFromUriId(id);

		UserInfo userInfo = userManager.getUserInfo(userId);
		EntityType type = entityManager.getEntityType(userInfo, id);
		deleteEntity(userId, entityId, type.getClassForType());
	}

	
	@Override
	public <T extends Entity> void deleteEntity(String userId, String id, Class<? extends T> clazz)
			throws NotFoundException, DatastoreException, UnauthorizedException {
		String entityId = UrlHelpers.getEntityIdFromUriId(id);

		UserInfo userInfo = userManager.getUserInfo(userId);
		// First get the entity we are deleting
		EntityType type = EntityType.getNodeTypeForClass(clazz);
		// Fetch the provider that will validate this entity.
		List<TypeSpecificMetadataProvider<Entity>> providers = metadataProviderFactory.getMetadataProvider(type);
		T entity = entityManager.getEntity(userInfo, entityId, clazz);
		entityManager.deleteEntity(userInfo, entityId);
		// Do extra cleanup as needed.
		if(providers != null) {
			for(TypeSpecificMetadataProvider<Entity> provider : providers) {
				provider.entityDeleted(entity);
			}
		}
		return;
	}
	
	@Override
	public void deleteEntityVersion(String userId, String id, Long versionNumber)
			throws NotFoundException, DatastoreException, UnauthorizedException, ConflictingUpdateException {

		String entityId = UrlHelpers.getEntityIdFromUriId(id);

		UserInfo userInfo = userManager.getUserInfo(userId);
		EntityType type = entityManager.getEntityType(userInfo, id);
		deleteEntityVersion(userId, entityId, versionNumber, type.getClassForType());
	}
	
	@Override
	public <T extends Entity> void deleteEntityVersion(String userId, String id,
			Long versionNumber, Class<? extends Entity> classForType) throws DatastoreException, NotFoundException, UnauthorizedException, ConflictingUpdateException {
		UserInfo userInfo = userManager.getUserInfo(userId);
		// First get the entity we are deleting
		EntityType type = EntityType.getNodeTypeForClass(classForType);
		// Fetch the provider that will validate this entity.
		List<TypeSpecificMetadataProvider<Entity>> providers = metadataProviderFactory.getMetadataProvider(type);
		T entity = (T) entityManager.getEntity(userInfo, id, classForType);
		entityManager.deleteEntityVersion(userInfo, id, versionNumber);
		// Do extra cleanup as needed.
		if(providers != null) {
			for(TypeSpecificMetadataProvider<Entity> provider : providers) {
				provider.entityDeleted(entity);
			}
		}
	}


	private <T extends Entity> void addServiceSpecificMetadata(T entity, HttpServletRequest request) {
		UrlHelpers.setAllUrlsForEntity(entity, request);
	}

	private void addServiceSpecificMetadata(String id, Annotations annotations,
			HttpServletRequest request) {
		annotations.setId(id); // the NON url-encoded id
		annotations.setUri(UrlHelpers.makeEntityPropertyUri(request));
	}

	@Override
	public Annotations getEntityAnnotations(String userId, String id,
			HttpServletRequest request) throws NotFoundException, DatastoreException, UnauthorizedException {
		UserInfo userInfo = userManager.getUserInfo(userId);
		return getEntityAnnotations(userInfo, id, request);
	}
	
	@Override
	public Annotations getEntityAnnotations(UserInfo info, String id,HttpServletRequest request) throws NotFoundException, DatastoreException, UnauthorizedException {
		Annotations annotations = entityManager.getAnnotations(info, id);
		addServiceSpecificMetadata(id, annotations, request);
		return annotations;
	}
	

	@Override
	public Annotations getEntityAnnotationsForVersion(String userId, String id,
			Long versionNumber, HttpServletRequest request)
			throws NotFoundException, DatastoreException, UnauthorizedException {
		UserInfo userInfo = userManager.getUserInfo(userId);
		Annotations annotations = entityManager.getAnnotationsForVersion(userInfo, id, versionNumber);
		addServiceSpecificMetadata(id, annotations, request);
		return annotations;
	}


	@Override
	public Annotations updateEntityAnnotations(String userId, String entityId,
			Annotations updatedAnnotations, HttpServletRequest request) throws ConflictingUpdateException, NotFoundException, DatastoreException, UnauthorizedException, InvalidModelException {
		if(updatedAnnotations.getId() == null) throw new IllegalArgumentException("Annotations must have a non-null id");
		UserInfo userInfo = userManager.getUserInfo(userId);
		entityManager.updateAnnotations(userInfo,entityId, updatedAnnotations);
		Annotations annos = entityManager.getAnnotations(userInfo, updatedAnnotations.getId());
		addServiceSpecificMetadata(updatedAnnotations.getId(), annos, request);
		return annos;
	}


	@Override
	public <T extends Entity> List<T> getEntityChildrenOfType(String userId,
			String parentId, Class<? extends T> childClass, HttpServletRequest request) throws DatastoreException, NotFoundException, UnauthorizedException {
		UserInfo userInfo = userManager.getUserInfo(userId);
		EntityType childType =  EntityType.getNodeTypeForClass(childClass);
		// For this case we want all children so build up the paging as such
		PaginatedParameters paging = new PaginatedParameters(0, Long.MAX_VALUE, null, true);
		BasicQuery query = QueryUtils.createChildrenOfTypePaginated(parentId, paging, childType);
		PaginatedResults<T> pageResult = executeQueryAndConvertToEntites(paging, request, childClass, userInfo, query);
		return pageResult.getResults();
	}
	
	@Override
	public <T extends Entity> PaginatedResults<T> getEntityChildrenOfTypePaginated(
			String userId, String parentId, Class<? extends T> clazz,
			PaginatedParameters paging, HttpServletRequest request)
			throws DatastoreException, NotFoundException, UnauthorizedException {
		EntityType childType =  EntityType.getNodeTypeForClass(clazz);
		UserInfo userInfo = userManager.getUserInfo(userId);
		BasicQuery query = QueryUtils.createChildrenOfTypePaginated(parentId, paging, childType);
		return executeQueryAndConvertToEntites(paging, request, clazz, userInfo, query);
	}
	
	
	@Override
	public <T extends Entity> Collection<T> aggregateEntityUpdate(String userId, String parentId, Collection<T> update,	HttpServletRequest request) throws NotFoundException,
			ConflictingUpdateException, DatastoreException,
			InvalidModelException, UnauthorizedException {
		if(update == null) return null;
		if(update.isEmpty()) return update;
		// First try the updated
		UserInfo userInfo = userManager.getUserInfo(userId);
		List<String> updatedIds = entityManager.aggregateEntityUpdate(userInfo, parentId, update);
		// Now create the update object
		List<T> newList = new ArrayList<T>();
		Class tClass = update.iterator().next().getClass();
		for(int i=0; i<updatedIds.size(); i++){
			newList.add((T) entityManager.getEntity(userInfo, updatedIds.get(i), tClass));
		}
		return newList;
	}

	/**
	 * Create a new entity
	 * <p>
	 * 
	 * @param userId
	 * @param newACL
	 * @param request
	 *            used to get the servlet URL prefix
	 * @return the newly created entity
	 * @throws InvalidModelException
	 * @throws DatastoreException
	 * @throws UnauthorizedException
	 * @throws NotFoundException 
	 * @throws ConflictingUpdateException 
	 */
	@Override
	public AccessControlList createEntityACL(String userId, AccessControlList newACL,
			HttpServletRequest request) throws DatastoreException,
			InvalidModelException, UnauthorizedException, NotFoundException, ConflictingUpdateException {

		UserInfo userInfo = userManager.getUserInfo(userId);		
		AccessControlList acl = permissionsManager.overrideInheritance(newACL, userInfo);
		acl.setUri(request.getRequestURI());
		return acl;
	}

	

	@Override
	public  AccessControlList getEntityACL(String entityId, String userId, HttpServletRequest request)
			throws NotFoundException, DatastoreException, UnauthorizedException, ACLInheritanceException {
		// First try the updated
		UserInfo userInfo = userManager.getUserInfo(userId);
		AccessControlList acl = permissionsManager.getACL(entityId, userInfo);
		
		acl.setUri(request.getRequestURI());

		return acl;
	}


	@Override
	public AccessControlList updateEntityACL(String userId,
			AccessControlList updated, HttpServletRequest request) throws DatastoreException, NotFoundException, InvalidModelException, UnauthorizedException, ConflictingUpdateException {
		// Resolve the user
		UserInfo userInfo = userManager.getUserInfo(userId);
		AccessControlList acl = permissionsManager.updateACL(updated, userInfo);
		acl.setUri(request.getRequestURI());
		return acl;
	}

	/**
	 * Delete a specific entity
	 * <p>
	 * 
	 * @param userId
	 * @param id the id of the node whose inheritance is to be restored
	 * @throws NotFoundException
	 * @throws DatastoreException
	 * @throws UnauthorizedException
	 * @throws ConflictingUpdateException 
	 */
	@Override
	public  void deleteEntityACL(String userId, String id)
			throws NotFoundException, DatastoreException, UnauthorizedException, ConflictingUpdateException {
		UserInfo userInfo = userManager.getUserInfo(userId);
		permissionsManager.restoreInheritance(id, userInfo);
	}


	@Override
	public QueryResults executeQueryWithAnnotations(String userId, BasicQuery query, HttpServletRequest request) throws DatastoreException, NotFoundException, UnauthorizedException {
		if(query == null) throw new IllegalArgumentException("Query cannot be null");
		// Lookup the user
		UserInfo userInfo = userManager.getUserInfo(userId);
		NodeQueryResults nodeResults = nodeQueryDao.executeQuery(query, userInfo);
		// done
		return new QueryResults(nodeResults.getAllSelectedData(), nodeResults.getTotalNumberOfResults());
	}
	
	/**
	 * determine whether a user has the given access type for a given entity
	 * @param nodeId
	 * @param userId
	 * @param accessType
	 * @return
	 * @throws NotFoundException
	 * @throws DatastoreException
	 * @throws UnauthorizedException 
	 */
	public <T extends Entity> boolean hasAccess(String entityId, String userId, HttpServletRequest request, String accessType) 
		throws NotFoundException, DatastoreException, UnauthorizedException {
		UserInfo userInfo = userManager.getUserInfo(userId);
		return permissionsManager.hasAccess(entityId, ACCESS_TYPE.valueOf(accessType), userInfo);
	}


	@Override
	public List<EntityHeader> getEntityPath(String userId, String entityId) throws DatastoreException, NotFoundException, UnauthorizedException {
		UserInfo userInfo = userManager.getUserInfo(userId);
		return entityManager.getEntityPath(userInfo, entityId);
	}


	@Override
	public EntityHeader getEntityHeader(String userId, String entityId)	throws NotFoundException, DatastoreException, UnauthorizedException {
		UserInfo userInfo = userManager.getUserInfo(userId);
		return entityManager.getEntityHeader(userInfo, entityId);
	}


	@Override
	public <T extends Entity> EntityHeader getEntityBenefactor(String entityId, String userId, HttpServletRequest request) throws NotFoundException,
			DatastoreException, UnauthorizedException, ACLInheritanceException {
		if(entityId == null) throw new IllegalArgumentException("EntityId cannot be null");
		if(userId == null) throw new IllegalArgumentException("UserId cannot be null");
		UserInfo userInfo = userManager.getUserInfo(userId);
		// First get the permissions benefactor
		String benefactor = permissionsManager.getPermissionBenefactor(entityId, userInfo);
		return getEntityHeader(userId, benefactor);
	}

	/**
	 * Get the entities which refer to the given version of the given entity
	 * @param userId
	 * @param entityId
	 * @param versionNumber
	 * @param offset ONE based pagination param
	 * @param limit pagination param
	 * @request
	 * @return the headers of the entities which have references to 'entityId'
	 * 
	 */
	@Override
	public PaginatedResults<EntityHeader> getEntityReferences(String userId, String entityId, Integer versionNumber, Integer offset, Integer limit, HttpServletRequest request)
			throws NotFoundException, DatastoreException {
		UserInfo userInfo = userManager.getUserInfo(userId);
		if (offset==null) offset = 1;
		if (limit==null) limit = Integer.MAX_VALUE;
		ServiceConstants.validatePaginationParams((long)offset, (long)limit);
		EntityHeaderQueryResults results = entityManager.getEntityReferences(userInfo, entityId, versionNumber, offset-1, limit);
		String urlPath = request.getRequestURL()==null ? "" : request.getRequestURL().toString();
		return new PaginatedResults(urlPath,  results.getEntityHeaders(), results.getTotalNumberOfResults(), offset, limit, /*sort*/null, /*ascending*/true);
	}


	@Override
	public UserEntityPermissions getUserEntityPermissions(String userId, String entityId) throws NotFoundException, DatastoreException {
		UserInfo userInfo = userManager.getUserInfo(userId);
		// TODO Auto-generated method stub
		return permissionsManager.getUserPermissionsForEntity(userInfo, entityId);
	}








}
