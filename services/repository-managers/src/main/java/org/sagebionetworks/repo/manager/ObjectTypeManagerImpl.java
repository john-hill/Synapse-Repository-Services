package org.sagebionetworks.repo.manager;

import org.sagebionetworks.repo.model.ACCESS_TYPE;
import org.sagebionetworks.repo.model.DataType;
import org.sagebionetworks.repo.model.DataTypeResponse;
import org.sagebionetworks.repo.model.ObjectType;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dbo.dao.DataTypeDao;
import org.sagebionetworks.repo.transactions.WriteTransactionReadCommitted;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.beans.factory.annotation.Autowired;

public class ObjectTypeManagerImpl implements ObjectTypeManager {

	@Autowired
	DataTypeDao dataTypeDao;

	@Autowired
	AuthorizationManager authorizationManager;

	@WriteTransactionReadCommitted
	@Override
	public DataTypeResponse changeObjectsDataType(UserInfo userInfo, String objectId, ObjectType objectType,
			DataType dataType) {
		ValidateArgument.required(userInfo, "User");
		ValidateArgument.required(objectId, "objectId");
		ValidateArgument.required(objectType, "ObjectType");
		ValidateArgument.required(dataType, "DataType");
		
		if(DataType.OPEN_DATA.equals(dataType)) {
			// must be an ACT member
			if (!authorizationManager.isACTTeamMemberOrAdmin(userInfo)) {
				throw new UnauthorizedException("Must be a member of the 'Synapse Access and Compliance Team' to change an object's DataType to: "+DataType.OPEN_DATA.name());
			}
		}else {
			// must have the update permission.
			if (!authorizationManager.canAccess(userInfo, objectId, objectType, ACCESS_TYPE.UPDATE).getAuthorized()) {
				throw new UnauthorizedException("Must have "+ACCESS_TYPE.UPDATE+" permission to change an object's DataType to : "+dataType.name());
			}
		}
		return dataTypeDao.changeDataType(userInfo.getId(), objectId, objectType, dataType);
	}

	@Override
	public DataType getObjectsDataType(String objectId, ObjectType objectType) {
		return dataTypeDao.getObjectDataType(objectId, objectType);
	}

}
