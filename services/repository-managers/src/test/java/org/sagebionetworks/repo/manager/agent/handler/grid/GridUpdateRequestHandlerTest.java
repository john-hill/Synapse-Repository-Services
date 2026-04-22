package org.sagebionetworks.repo.manager.agent.handler.grid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.manager.agent.handler.ReturnControlEvent;
import org.sagebionetworks.repo.manager.agent.parameter.Parameter;
import org.sagebionetworks.repo.manager.grid.GridManager;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.GridHeader;
import org.sagebionetworks.repo.manager.grid.internal.replica.view.GridReplicaViewManager;
import org.sagebionetworks.repo.model.agent.GridAgentSessionContext;
import org.sagebionetworks.repo.model.grid.EventSource;
import org.sagebionetworks.repo.model.grid.GridConnectionInfo;
import org.sagebionetworks.repo.model.grid.update.GridUpdateRequest;
import org.sagebionetworks.repo.model.grid.update.LiteralSetValue;
import org.sagebionetworks.repo.model.grid.update.Update;
import org.sagebionetworks.repo.model.grid.update.UpdateBatch;
import org.sagebionetworks.repo.model.grid.query.CellValueFilter;
import org.sagebionetworks.repo.model.grid.query.CellValueOperator;
import org.sagebionetworks.repo.model.grid.query.RowSelectionFilter;
import org.sagebionetworks.repo.model.jdo.JDOSecondaryPropertyUtils;

@ExtendWith(MockitoExtension.class)
public class GridUpdateRequestHandlerTest {

	@Mock
	private GridManager mockGridManager;
	@Mock
	private GridReplicaViewManager mockGridViewManager;
	@InjectMocks
	@Spy
	private GridUpdateRequestHandler handler;

	private GridAgentSessionContext agentContext;
	private ReturnControlEvent event;
	private String gridSessionId;
	private Long usersReplicaId;
	private Long agentsReplicaId;
	private GridUpdateRequest updateRequest;
	private JSONObject updateRequestRaw;
	private GridConnectionInfo internalConnection;
	private GridConnectionInfo agentConnection;
	private GridHeader header;

	@BeforeEach
	public void before() {
		gridSessionId = "g123";
		usersReplicaId = 100L;
		agentsReplicaId = 200L;
		agentContext = new GridAgentSessionContext().setGridSessionId(gridSessionId).setUsersReplicaId(usersReplicaId)
				.setAgentsReplicaId(agentsReplicaId);
		event = new ReturnControlEvent(123L, "action", "function", List.<Parameter>of(), null, agentContext);
		updateRequest = new GridUpdateRequest().setUpdate(new UpdateBatch().setBatch(List.of(
				// one
				new Update().setSet(List.of(new LiteralSetValue().setColumnName("a").setValue(true)))
						.setFilters(List.of(new RowSelectionFilter().setIsSelected(true))).setLimit(10L),
				// two
				new Update().setSet(List.of(new LiteralSetValue().setColumnName("b").setValue(1))).setFilters(
						List.of(new CellValueFilter().setColumnName("b").setOperator(CellValueOperator.IS_UNDEFINED)))
		// end
		)));
		updateRequestRaw = JDOSecondaryPropertyUtils.createJSONObjectForEntity(updateRequest);
		internalConnection = new GridConnectionInfo().setConnectionId("internal");
		agentConnection = new GridConnectionInfo().setConnectionId("agent");
		header = new GridHeader().setClockSequenceMaximum(123L);
	}

	@Test
	public void testHandleEvent() throws Exception {
		doReturn(updateRequestRaw).when(handler).extractRequest(event);
		doReturn(agentContext).when(handler).getSessionContext(event);
		doReturn(internalConnection).when(handler).getInternalConnection(agentContext);
		doReturn(header).when(handler).getGridHeader(agentContext, internalConnection);
		doReturn(agentConnection).when(handler).getAgentConnection(agentContext);

		when(mockGridManager.executeGridUpdate(eq(header), eq(agentConnection), any(JSONObject.class)))
				.thenReturn(2L).thenReturn(3L);

		// call under test
		String result = handler.handleEvent(event);

		assertEquals("{\"updateResults\":[2,3],\"totalRowsUpdated\":5}", result);

		ArgumentCaptor<JSONObject> jsonCaptor = ArgumentCaptor.forClass(JSONObject.class);
		verify(mockGridManager, times(2)).executeGridUpdate(eq(header), eq(agentConnection), jsonCaptor.capture());

		List<JSONObject> capturedJsons = jsonCaptor.getAllValues();
		assertEquals(2, capturedJsons.size());
		assertEquals(updateRequest.getUpdate().getBatch().get(0),
				JDOSecondaryPropertyUtils.createObjectFromJSON(Update.class, capturedJsons.get(0).toString()));
		assertEquals(updateRequest.getUpdate().getBatch().get(1),
				JDOSecondaryPropertyUtils.createObjectFromJSON(Update.class, capturedJsons.get(1).toString()));
	}

	@Test
	public void testGetSessionContext() {
		// call under test
		GridAgentSessionContext context = handler.getSessionContext(event);
		assertEquals(agentContext, context);
	}

	@Test
	public void testGetSessionContextWithNoContext() {
		event = new ReturnControlEvent(123L, "action", "function", List.<Parameter>of(), null, null);

		String message = assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			handler.getSessionContext(event);
		}).getMessage();

		assertEquals("GridAgentSessionContext cannot be null", message);
	}

	@Test
	public void testGetInternalConnection() {
		when(mockGridManager.getSingletonConnection(gridSessionId, EventSource.INTERNAL))
				.thenReturn(Optional.of(internalConnection));

		// call under test
		GridConnectionInfo connection = handler.getInternalConnection(agentContext);
		assertEquals(internalConnection, connection);
	}

	@Test
	public void testGetInternalConnectionWithNoConnection() {
		when(mockGridManager.getSingletonConnection(gridSessionId, EventSource.INTERNAL)).thenReturn(Optional.empty());

		String message = assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			handler.getInternalConnection(agentContext);
		}).getMessage();

		assertEquals("Cannot get an internal grid connection.", message);
	}

	@Test
	public void testGetAgentConnection() {
		when(mockGridManager.getConnection(gridSessionId, agentsReplicaId)).thenReturn(Optional.of(agentConnection));

		// call under test
		GridConnectionInfo connection = handler.getAgentConnection(agentContext);
		assertEquals(agentConnection, connection);
	}

	@Test
	public void testGetAgentConnectionWithNoConnection() {
		when(mockGridManager.getConnection(gridSessionId, agentsReplicaId)).thenReturn(Optional.empty());

		String message = assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			handler.getAgentConnection(agentContext);
		}).getMessage();

		assertEquals("Cannot get an agent grid connection.", message);
	}

	@Test
	public void testGetGridHeader() {
		when(mockGridViewManager.readHeader(gridSessionId, internalConnection.getReplicaId(), usersReplicaId))
				.thenReturn(Optional.of(header));

		// call under test
		GridHeader result = handler.getGridHeader(agentContext, internalConnection);
		assertEquals(header, result);
	}

	@Test
	public void testGetGridHeaderWithNoHeader() {
		when(mockGridViewManager.readHeader(gridSessionId, internalConnection.getReplicaId(), usersReplicaId))
				.thenReturn(Optional.empty());

		String message = assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			handler.getGridHeader(agentContext, internalConnection);
		}).getMessage();

		assertEquals("Cannot read the grid header.", message);
	}

	@Test
	public void testExtractRequest() {
		GridUpdateRequest expected = new GridUpdateRequest().setUpdate(new UpdateBatch().setBatch(
				List.of(new Update().setSet(List.of(new LiteralSetValue().setColumnName("a").setValue(1))))));
		JSONObject rawExpected = JDOSecondaryPropertyUtils.createJSONObjectForEntity(expected);
		JSONObject update = rawExpected.getJSONObject("update");
		event = new ReturnControlEvent(1L, "group", "function", null,
				List.of(new Parameter("update", "object", update.toString())),
				new GridAgentSessionContext().setAgentsReplicaId(123L));
		// call under test
		JSONObject result = handler.extractRequest(event);
		assertTrue(rawExpected.similar(result));
	}

	@Test
	public void testExtractRequestWithJsonArrayValue() {
		GridUpdateRequest expected = new GridUpdateRequest().setUpdate(new UpdateBatch().setBatch(List.of(new Update()
				.setSet(List.of(new LiteralSetValue().setColumnName("a").setValue(new JSONArray("[1,2,3]")))))));
		JSONObject rawExpected = JDOSecondaryPropertyUtils.createJSONObjectForEntity(expected);
		JSONObject update = rawExpected.getJSONObject("update");
		event = new ReturnControlEvent(1L, "group", "function", null,
				List.of(new Parameter("update", "object", update.toString())),
				new GridAgentSessionContext().setAgentsReplicaId(123L));
		// call under test
		JSONObject result = handler.extractRequest(event);
		assertTrue(rawExpected.similar(result));
	}

	@Test
	public void testExtractRequestWithNullBody() {
		event = new ReturnControlEvent(1L, "group", "function", null, null,
				new GridAgentSessionContext().setAgentsReplicaId(123L));
		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> handler.extractRequest(event));
		assertEquals("Request body cannot be null.", ex.getMessage());
	}
}
