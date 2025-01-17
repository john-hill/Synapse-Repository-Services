package org.sagebionetworks.repo.manager.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;

import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.LoggerProvider;
import org.sagebionetworks.repo.manager.agent.handler.HttpCode;
import org.sagebionetworks.repo.manager.agent.handler.HttpMethod;
import org.sagebionetworks.repo.manager.agent.handler.PutEntityAnnotationsHandler;
import org.sagebionetworks.repo.manager.agent.handler.ReturnControlEvent;
import org.sagebionetworks.repo.manager.agent.parameter.Parameter;
import org.sagebionetworks.repo.model.annotation.v2.Annotations;
import org.sagebionetworks.repo.service.EntityService;
import org.sagebionetworks.schema.adapter.JSONObjectAdapterException;
import org.sagebionetworks.schema.adapter.org.json.EntityFactory;

@ExtendWith(MockitoExtension.class)
public class PutEntityAnnotationsHandlerTest {

	@Mock
	private EntityService mockEntityService;
	@Mock
	private LoggerProvider mockLoggerProvider;
	@Mock
	private Logger mockLog;

	private PutEntityAnnotationsHandler handler;
	private ReturnControlEvent event;
	private Long userId;
	private String entityId;
	private List<Parameter> parameters;
	private Annotations body;

	@BeforeEach
	private void before() throws JSONObjectAdapterException {
		when(mockLoggerProvider.getLogger(PutEntityAnnotationsHandler.class.getName())).thenReturn(mockLog);
		handler = new PutEntityAnnotationsHandler(mockEntityService, mockLoggerProvider);
		userId = 123L;
		entityId = "syn456";
		parameters = List.of(new Parameter("entityId", "string", entityId));
		body = new Annotations().setId(entityId).setEtag("etag");
		String bodyString = EntityFactory.createJSONStringForEntity(body);
		event = new ReturnControlEvent(userId, handler.getActionGroup(), handler.getFunction(), parameters, bodyString);
	}

	@Test
	public void testGetActionGroup() {
		assertEquals("org_sage_one", handler.getActionGroup());
	}

	@Test
	public void testGetFunction() {
		assertEquals("PUT /entity/{entityId}/annotations", handler.getFunction());
	}

	@Test
	public void testGetHttpMethod() {
		assertEquals(HttpMethod.put, handler.getHttpMethod());
	}

	@Test
	public void testGetSuccessCode() {
		assertEquals(HttpCode.created, handler.getSuccessHttpCode());
	}

	@Test
	public void testGetPath() {
		assertEquals("/entity/{entityId}/annotations", handler.getPath());
	}

	@Test
	public void testHandleEvent() throws Exception {

		when(mockEntityService.updateEntityAnnotations(userId, entityId, body))
				.thenReturn(new Annotations().setId(entityId).setEtag("etag2"));

		// call under test
		String result = handler.handleEvent(event);
		verify(mockLog).info("Agent called '{}' entityId = {} userId = {} requestBody = '{}'", handler.getFunction(),
				entityId, event.getRunAsUserId(), body);

		assertEquals("{\"id\":\"syn456\",\"etag\":\"etag2\"}", result);

	}

	@Test
	public void testHandleEventWithNoParam() throws Exception {

		event = new ReturnControlEvent(userId, handler.getActionGroup(), handler.getFunction(), Collections.emptyList(),
				"");

		String message = assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			handler.handleEvent(event);
		}).getMessage();
		assertEquals("Parameter 'entityId' of type string is required", message);

		verifyZeroInteractions(mockLog, mockEntityService);

	}
}
