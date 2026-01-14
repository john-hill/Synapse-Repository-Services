package org.sagebionetworks.repo.model.dbo.asynch;

import org.apache.commons.codec.digest.DigestUtils;
import org.sagebionetworks.repo.model.grid.GridViewSynchronizationRequest;
import org.sagebionetworks.repo.model.jdo.JDOSecondaryPropertyUtils;

public class GridViewSynchronizationProvider implements FifoRequestProvider<GridViewSynchronizationRequest> {

	@Override
	public String getMessageDeduplicationId(GridViewSynchronizationRequest requestBody) {
		return DigestUtils.md5Hex(JDOSecondaryPropertyUtils.createJSONFromObject(requestBody));
	}

	@Override
	public String getMessageGroupId(GridViewSynchronizationRequest requestBody) {
		return requestBody.getSessionId();
	}

}
