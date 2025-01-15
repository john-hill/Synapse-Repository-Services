package org.sagebionetworks.repo.manager.agent.handler;

import org.apache.logging.log4j.Logger;
import org.sagebionetworks.LoggerProvider;
import org.sagebionetworks.repo.manager.agent.parameter.ParameterUtils;
import org.sagebionetworks.repo.service.EntityService;
import org.sagebionetworks.schema.adapter.org.json.EntityFactory;
import org.springframework.stereotype.Service;

@Service
public class GetEntityAnnotationsHandler implements OpenApiReturnControlHandler {

	private final EntityService entityService;
	private final Logger log;

	public GetEntityAnnotationsHandler(EntityService entityService, LoggerProvider loggerProvider) {
		super();
		this.entityService = entityService;
		log = loggerProvider.getLogger(GetEntityAnnotationsHandler.class.getName());
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
		String json = EntityFactory.createJSONStringForEntity(
				entityService.getEntityAnnotations(event.getRunAsUserId(), synId, includeDerived));
		log.info("Agent called '{}' entityId = {} userId = {} results = '{}'", this.getFunction(), synId,
				event.getRunAsUserId(), json);
		return json;
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
