package org.sagebionetworks.repo.manager.agent.handler;

import org.sagebionetworks.repo.manager.agent.parameter.ParameterUtils;
import org.sagebionetworks.repo.service.EntityService;
import org.sagebionetworks.schema.adapter.org.json.EntityFactory;
import org.springframework.stereotype.Service;

@Service
public class GetEntityAnnotationsHandler implements OpenApiReturnControlHandler {

	private final EntityService entityService;

	public GetEntityAnnotationsHandler(EntityService entityService) {
		super();
		this.entityService = entityService;
	}

	@Override
	public String getActionGroup() {
		return "org_sage_one";
	}

	@Override
	public boolean needsWriteAccess() {
		return false;
	}

	@Override
	public String handleEvent(ReturnControlEvent event) throws Exception {
		String synId = ParameterUtils.extractParameter(String.class, "entityId", event.getParameters())
				.orElseThrow(() -> new IllegalArgumentException("Parameter 'entityId' of type string is required"));

		boolean includeDerived = true;
		return EntityFactory.createJSONStringForEntity(
				entityService.getEntityAnnotations(event.getRunAsUserId(), synId, includeDerived));
	}

	@Override
	public String getPath() {
		return "/entity/{entityId}/annotations";
	}

	@Override
	public HttpMethod getHttpMethod() {
		return HttpMethod.get;
	}

	@Override
	public HttpCode getSuccessHttpCode() {
		return HttpCode.ok;
	}

}
