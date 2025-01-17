package org.sagebionetworks.repo.manager.agent.handler;

import org.apache.logging.log4j.Logger;
import org.sagebionetworks.LoggerProvider;
import org.sagebionetworks.repo.manager.agent.parameter.ParameterUtils;
import org.sagebionetworks.repo.model.annotation.v2.Annotations;
import org.sagebionetworks.repo.service.EntityService;
import org.sagebionetworks.schema.adapter.org.json.EntityFactory;
import org.springframework.stereotype.Service;

@Service
public class PutEntityAnnotationsHandler implements OpenApiReturnControlHandler {

	private final EntityService entityService;
	private final Logger log;

	public PutEntityAnnotationsHandler(EntityService entityService, LoggerProvider loggerProvider) {
		super();
		this.entityService = entityService;
		log = loggerProvider.getLogger(PutEntityAnnotationsHandler.class.getName());
	}

	@Override
	public String getActionGroup() {
		return "org_sage_one";
	}

	@Override
	public boolean needsWriteAccess() {
		return true;
	}

	@Override
	public String handleEvent(ReturnControlEvent event) throws Exception {
		String synId = ParameterUtils.extractParameter(String.class, "entityId", event.getParameters())
				.orElseThrow(() -> new IllegalArgumentException("Parameter 'entityId' of type string is required"));

		Annotations body = EntityFactory.createEntityFromJSONString(
				event.getRequestBody().orElseThrow(() -> new IllegalArgumentException("Request body cannot be null")),
				Annotations.class);
		log.info("Agent called '{}' entityId = {} userId = {} requestBody = '{}'", this.getFunction(), synId,
				event.getRunAsUserId(), body);
		return EntityFactory
				.createJSONStringForEntity(entityService.updateEntityAnnotations(event.getRunAsUserId(), synId, body));
	}

	@Override
	public String getPath() {
		return "/entity/{entityId}/annotations";
	}

	@Override
	public HttpMethod getHttpMethod() {
		return HttpMethod.put;
	}

	@Override
	public HttpCode getSuccessHttpCode() {
		return HttpCode.created;
	}

}
