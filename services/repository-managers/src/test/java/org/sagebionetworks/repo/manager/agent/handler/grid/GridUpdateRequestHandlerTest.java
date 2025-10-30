package org.sagebionetworks.repo.manager.agent.handler.grid;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
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
import org.sagebionetworks.repo.manager.grid.internal.replica.change.IntendedChangePublisher;
import org.sagebionetworks.repo.manager.grid.internal.replica.change.PatchBuilderPublisher;
import org.sagebionetworks.repo.manager.grid.internal.replica.change.UpdateRowChange;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.Column;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.GridHeader;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.RowData;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.RowObject;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.RowView;
import org.sagebionetworks.repo.manager.grid.internal.replica.view.GridReplicaViewManager;
import org.sagebionetworks.repo.manager.grid.internal.replica.view.query.QueryElement;
import org.sagebionetworks.repo.model.agent.GridAgentSessionContext;
import org.sagebionetworks.repo.model.grid.EventSource;
import org.sagebionetworks.repo.model.grid.GridConnectionInfo;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;
import org.sagebionetworks.repo.model.grid.query.RowIsValidFilter;
import org.sagebionetworks.repo.model.grid.update.GridUpdateRequest;
import org.sagebionetworks.repo.model.grid.update.SetValue;
import org.sagebionetworks.repo.model.grid.update.Update;
import org.sagebionetworks.repo.model.jdo.JDOSecondaryPropertyUtils;

@ExtendWith(MockitoExtension.class)
public class GridUpdateRequestHandlerTest {

	@Mock
	private GridManager mockGridManager;
	@Mock
	private GridReplicaViewManager mockGridViewManager;
	@Mock
	private PatchBuilderPublisher mockPatchBuilderPublisher;
	@Mock
	private IntendedChangePublisher mockIntendedChangePublisher;
	@InjectMocks
	@Spy
	private GridUpdateRequestHandler handler;

	private GridAgentSessionContext agentContext;
	private ReturnControlEvent event;
	private String gridSessionId;
	private Long usersReplicaId;
	private Long agentsReplicaId;

	@BeforeEach
	public void before() {
		gridSessionId = "g123";
		usersReplicaId = 100L;
		agentsReplicaId = 200L;
		agentContext = new GridAgentSessionContext().setGridSessionId(gridSessionId).setUsersReplicaId(usersReplicaId)
				.setAgentsReplicaId(agentsReplicaId);
		event = new ReturnControlEvent(123L, "action", "function", List.<Parameter>of(), null, agentContext);
	}

	private RowView buildRow(long rep, long seq) {
		RowData data = new RowData().setVectorId(new LogicalTimestamp().setReplicaId(rep).setSequenceNumber(seq));
		RowObject ro = new RowObject().setData(data);
		return new RowView().setRowObject(ro);
	}

	private GridHeader buildHeader(List<Column> cols) {
		return new GridHeader().setOrderedColumns(cols).setClockSequenceMaximum(999L);
	}

	@Test
	public void testHandleEventWithSuccessAndNoFiltersAndMultipleRows() throws Exception {
		List<SetValue> setValues = List.of(new SetValue().setColumnName("colA").setValue("A1"),
				new SetValue().setColumnName("colB").setValue("B1"));
		GridUpdateRequest request = new GridUpdateRequest()
				.setUpdate(new Update().setSet(setValues).setFilters(null).setLimit(10L));

		doReturn(request).when(handler).extractRequest(event);
		GridConnectionInfo internalConn = new GridConnectionInfo().setReplicaId(11L).setSessionId(gridSessionId)
				.setConnectionId("int-1").setSource(EventSource.INTERNAL);
		when(mockGridManager.getSingletonConnection(gridSessionId, EventSource.INTERNAL))
				.thenReturn(Optional.of(internalConn));
		GridConnectionInfo agentConn = new GridConnectionInfo().setReplicaId(agentsReplicaId)
				.setSessionId(gridSessionId).setConnectionId("agent-1").setSource(EventSource.AGENT);
		when(mockGridManager.getConnection(gridSessionId, agentsReplicaId)).thenReturn(Optional.of(agentConn));
		GridHeader header = buildHeader(List.of(new Column().setName("colA").setVectorIndex(0),
				new Column().setName("colB").setVectorIndex(1)));
		when(mockGridViewManager.readHeader(gridSessionId, internalConn.getReplicaId(), usersReplicaId))
				.thenReturn(Optional.of(header));
		List<RowView> rows = List.of(buildRow(1L, 100L), buildRow(1L, 101L));
		when(mockGridViewManager.getQueryIterator(eq(header), any(QueryElement.class))).thenReturn(rows.iterator());
		Integer[] idxArr = new Integer[] { 4, 1 };
		doReturn(idxArr).when(handler).createIndexArray(setValues, header);
		doReturn(mockIntendedChangePublisher).when(handler).newIntendedChangePublisher(agentConn,
				header.getClockSequenceMaximum(), mockPatchBuilderPublisher);
		doAnswer(inv -> "response:" + inv.getArgument(0)).when(handler).buildResponseJSON(anyLong());

		// call under test
		String result = handler.handleEvent(event);

		assertEquals("response:2", result);
		ArgumentCaptor<UpdateRowChange> cap = ArgumentCaptor.forClass(UpdateRowChange.class);
		verify(mockIntendedChangePublisher, times(2)).publish(cap.capture());
		for (int i = 0; i < cap.getAllValues().size(); i++) {
			UpdateRowChange c = cap.getAllValues().get(i);
			assertArrayEquals(idxArr, c.getRowVectorIndex());
			assertEquals(rows.get(i).getRowObject().getData().getVectorId(), c.getRowVectorId());
			JSONArray u = c.getRowData();
			assertEquals(2, u.length());
			assertEquals("A1", u.get(0));
			assertEquals("B1", u.get(1));
		}
		verify(mockIntendedChangePublisher).close();
	}

	@Test
	public void testHandleEventWithSuccessWithNullValueAndNonNullFilters() throws Exception {
		List<SetValue> setValues = List.of(new SetValue().setColumnName("colA").setValue(null));
		GridUpdateRequest request = new GridUpdateRequest().setUpdate(
				new Update().setSet(setValues).setFilters(List.of(new RowIsValidFilter().setValue(true))).setLimit(5L));
		doReturn(request).when(handler).extractRequest(event);
		GridConnectionInfo internalConn = new GridConnectionInfo().setReplicaId(11L).setSessionId(gridSessionId)
				.setConnectionId("int-2").setSource(EventSource.INTERNAL);
		when(mockGridManager.getSingletonConnection(gridSessionId, EventSource.INTERNAL))
				.thenReturn(Optional.of(internalConn));
		GridConnectionInfo agentConn = new GridConnectionInfo().setReplicaId(agentsReplicaId)
				.setSessionId(gridSessionId).setConnectionId("agent-2").setSource(EventSource.AGENT);
		when(mockGridManager.getConnection(gridSessionId, agentsReplicaId)).thenReturn(Optional.of(agentConn));
		GridHeader header = buildHeader(List.of(new Column().setName("colA").setVectorIndex(5)));
		when(mockGridViewManager.readHeader(gridSessionId, internalConn.getReplicaId(), usersReplicaId))
				.thenReturn(Optional.of(header));
		List<RowView> rows = List.of(buildRow(2L, 200L));
		when(mockGridViewManager.getQueryIterator(eq(header), any(QueryElement.class))).thenReturn(rows.iterator());
		Integer[] idxArr = new Integer[] { 7 };
		doReturn(idxArr).when(handler).createIndexArray(setValues, header);
		doReturn(mockIntendedChangePublisher).when(handler).newIntendedChangePublisher(agentConn,
				header.getClockSequenceMaximum(), mockPatchBuilderPublisher);
		doAnswer(inv -> "resp:" + inv.getArgument(0)).when(handler).buildResponseJSON(anyLong());

		// call under test
		String result = handler.handleEvent(event);

		assertEquals("resp:1", result);
		ArgumentCaptor<UpdateRowChange> cap = ArgumentCaptor.forClass(UpdateRowChange.class);
		verify(mockIntendedChangePublisher).publish(cap.capture());
		UpdateRowChange c = cap.getValue();
		assertEquals(rows.get(0).getRowObject().getData().getVectorId(), c.getRowVectorId());
		assertArrayEquals(idxArr, c.getRowVectorIndex());
		assertEquals(1, c.getRowData().length());
		assertEquals(JSONObject.NULL, c.getRowData().get(0));
		verify(mockIntendedChangePublisher).close();
	}

	@Test
	public void testHandleEventWithSuccess_ZeroRows_NoPublish() throws Exception {
		GridUpdateRequest request = new GridUpdateRequest().setUpdate(new Update().setFilters(null).setLimit(10L)
				.setSet(List.of(new SetValue().setColumnName("colA").setValue("v"))));
		doReturn(request).when(handler).extractRequest(event);
		GridConnectionInfo internalConn = new GridConnectionInfo().setReplicaId(11L).setSessionId(gridSessionId)
				.setConnectionId("int-3").setSource(EventSource.INTERNAL);
		when(mockGridManager.getSingletonConnection(gridSessionId, EventSource.INTERNAL))
				.thenReturn(Optional.of(internalConn));
		GridConnectionInfo agentConn = new GridConnectionInfo().setReplicaId(agentsReplicaId)
				.setSessionId(gridSessionId).setConnectionId("agent-3").setSource(EventSource.AGENT);
		when(mockGridManager.getConnection(gridSessionId, agentsReplicaId)).thenReturn(Optional.of(agentConn));
		GridHeader header = buildHeader(List.of(new Column().setName("colA").setVectorIndex(0)));
		when(mockGridViewManager.readHeader(gridSessionId, internalConn.getReplicaId(), usersReplicaId))
				.thenReturn(Optional.of(header));
		when(mockGridViewManager.getQueryIterator(eq(header), any(QueryElement.class)))
				.thenReturn(List.<RowView>of().iterator());
		doReturn(new Integer[] { 0 }).when(handler).createIndexArray(any(), eq(header));
		doReturn(mockIntendedChangePublisher).when(handler).newIntendedChangePublisher(agentConn,
				header.getClockSequenceMaximum(), mockPatchBuilderPublisher);
		doAnswer(inv -> "res:" + inv.getArgument(0)).when(handler).buildResponseJSON(anyLong());

		// call under test
		String result = handler.handleEvent(event);

		assertEquals("res:0", result);
		verify(mockIntendedChangePublisher, never()).publish(any());
		verify(mockIntendedChangePublisher).close();
	}

	@Test
	public void testHandleEventWithMissingContext() throws Exception {
		ReturnControlEvent bad = new ReturnControlEvent(123L, "ag", "fn", List.<Parameter>of(), null, null);
		GridUpdateRequest request = new GridUpdateRequest().setUpdate(new Update());
		doReturn(request).when(handler).extractRequest(bad);
		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> handler.handleEvent(bad));
		assertEquals("GridAgentSessionContext cannot be null", ex.getMessage());
	}

	@Test
	public void testHandleEventWithMissingUpdate() throws Exception {
		GridUpdateRequest request = new GridUpdateRequest().setUpdate(null);
		doReturn(request).when(handler).extractRequest(event);
		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> handler.handleEvent(event));
		assertEquals("update is required.", ex.getMessage());
	}

	@Test
	public void testHandleEventWithMissingInternalConnection() throws Exception {
		GridUpdateRequest request = new GridUpdateRequest().setUpdate(new Update());
		doReturn(request).when(handler).extractRequest(event);
		when(mockGridManager.getSingletonConnection(gridSessionId, EventSource.INTERNAL)).thenReturn(Optional.empty());
		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> handler.handleEvent(event));
		assertEquals("Cannot get a grid connection.", ex.getMessage());
	}

	@Test
	public void testHandleEventWithMissingHeader() throws Exception {
		GridUpdateRequest request = new GridUpdateRequest().setUpdate(new Update());
		doReturn(request).when(handler).extractRequest(event);
		GridConnectionInfo internalConn = new GridConnectionInfo().setReplicaId(11L).setSessionId(gridSessionId)
				.setConnectionId("int-4").setSource(EventSource.INTERNAL);
		when(mockGridManager.getSingletonConnection(gridSessionId, EventSource.INTERNAL))
				.thenReturn(Optional.of(internalConn));
		when(mockGridViewManager.readHeader(gridSessionId, internalConn.getReplicaId(), usersReplicaId))
				.thenReturn(Optional.empty());
		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> handler.handleEvent(event));
		assertEquals("Grid session does not exist", ex.getMessage());
	}

	@Test
	public void testHandleEventWithMissingAgentConnection() throws Exception {
		GridUpdateRequest request = new GridUpdateRequest().setUpdate(new Update());
		doReturn(request).when(handler).extractRequest(event);
		GridConnectionInfo internalConn = new GridConnectionInfo().setReplicaId(11L).setSessionId(gridSessionId)
				.setConnectionId("int-5").setSource(EventSource.INTERNAL);
		when(mockGridManager.getSingletonConnection(gridSessionId, EventSource.INTERNAL))
				.thenReturn(Optional.of(internalConn));
		GridHeader header = buildHeader(List.of());
		when(mockGridViewManager.readHeader(gridSessionId, internalConn.getReplicaId(), usersReplicaId))
				.thenReturn(Optional.of(header));
		when(mockGridManager.getConnection(gridSessionId, agentsReplicaId)).thenReturn(Optional.empty());
		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> handler.handleEvent(event));
		assertEquals("Grid connection does not exist for the agent replica.", ex.getMessage());
	}

	@Test
	public void testCreateIndexArray() {
		List<SetValue> set = List.of(new SetValue().setColumnName("a").setValue("1"),
				new SetValue().setColumnName("b").setValue(3));
		GridHeader header = new GridHeader().setOrderedColumns(List.of(new Column().setName("a").setVectorIndex(2),
				new Column().setName("c").setVectorIndex(0), new Column().setName("b").setVectorIndex(1)));

		// call under test
		Integer[] results = handler.createIndexArray(set, header);
		assertArrayEquals(new Integer[] { 2, 1 }, results);
	}

	@Test
	public void testCreateIndexArrayWithNullSet() {
		List<SetValue> set = null;
		GridHeader header = new GridHeader().setOrderedColumns(List.of(new Column().setName("a").setVectorIndex(2),
				new Column().setName("c").setVectorIndex(0), new Column().setName("b").setVectorIndex(1)));

		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> handler.createIndexArray(set, header));
		assertEquals("set is required.", ex.getMessage());
	}

	@Test
	public void testCreateIndexArrayWithNullHeader() {
		List<SetValue> set = List.of(new SetValue().setColumnName("a").setValue("1"),
				new SetValue().setColumnName("b").setValue(3));
		GridHeader header = null;
		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> handler.createIndexArray(set, header));
		assertEquals("header is required.", ex.getMessage());
	}

	@Test
	public void testCreateIndexArrayWithHeaderColumnsNull() {
		List<SetValue> set = List.of(new SetValue().setColumnName("a").setValue("1"),
				new SetValue().setColumnName("b").setValue(3));
		GridHeader header = new GridHeader().setOrderedColumns(null);
		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> handler.createIndexArray(set, header));
		assertEquals("header.orderedColumns is required.", ex.getMessage());
	}

	@Test
	public void testCreateIndexArrayWithNotFound() {
		List<SetValue> set = List.of(new SetValue().setColumnName("a").setValue("1"),
				new SetValue().setColumnName("x").setValue(3));
		GridHeader header = new GridHeader().setOrderedColumns(List.of(new Column().setName("a").setVectorIndex(2),
				new Column().setName("c").setVectorIndex(0), new Column().setName("b").setVectorIndex(1)));
		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> handler.createIndexArray(set, header));
		assertEquals("Column name: x not found.", ex.getMessage());
	}

	@Test
	public void testExtractRequest() {
		GridUpdateRequest expected = new GridUpdateRequest()
				.setUpdate(new Update().setSet(List.of(new SetValue().setColumnName("a").setValue(1))));
		String json = JDOSecondaryPropertyUtils.createJSONFromObject(expected.getUpdate());
		event = new ReturnControlEvent(1L, "group", "function", null, List.of(new Parameter("update", "object", json)),
				new GridAgentSessionContext().setAgentsReplicaId(123L));
		// call under test
		GridUpdateRequest result = handler.extractRequest(event);
		assertEquals(expected, result);
	}

	@Test
	public void testExtractRequestWithJsonArrayValue() {
		GridUpdateRequest expected = new GridUpdateRequest().setUpdate(
				new Update().setSet(List.of(new SetValue().setColumnName("a").setValue(new JSONArray("[1,2,3]")))));
		// the agent can provide a value that is
		String json = "{\"set\":[{\"columnName\":\"a\",\"value\":[1,2,3]}]}";
		event = new ReturnControlEvent(1L, "group", "function", null, List.of(new Parameter("update", "object", json)),
				new GridAgentSessionContext().setAgentsReplicaId(123L));
		// call under test
		GridUpdateRequest result = handler.extractRequest(event);
		assertEquals(expected.toString(), result.toString());
		Object value = result.getUpdate().getSet().get(0).getValue();
		assertEquals("[1,2,3]", value.toString());
	}

	@Test
	public void testExtractRequestWithJsonObjectValue() {
		GridUpdateRequest expected = new GridUpdateRequest().setUpdate(new Update()
				.setSet(List.of(new SetValue().setColumnName("a").setValue(new JSONObject("{\"key\":true}")))));
		// the agent can provide a value that is
		String json = "{\"set\":[{\"columnName\":\"a\",\"value\":{\"key\":true}}]}";
		event = new ReturnControlEvent(1L, "group", "function", null, List.of(new Parameter("update", "object", json)),
				new GridAgentSessionContext().setAgentsReplicaId(123L));
		// call under test
		GridUpdateRequest result = handler.extractRequest(event);
		assertEquals(expected.toString(), result.toString());
	}

	@Test
	public void testExtractRequestWithNullBody() {
		event = new ReturnControlEvent(1L, "group", "function", null, null,
				new GridAgentSessionContext().setAgentsReplicaId(123L));
		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> handler.extractRequest(event));
		assertEquals("Request body cannot be null.", ex.getMessage());
	}

	@Test
	public void testHandleEventWithCellValueFilterNullValue() throws Exception {
		String json = "{\"set\":[{\"columnName\":\"lastFed\",\"value\":\"2024-01-15\"}],\"filters\":[{\"concreteType\":\"org.sagebionetworks.repo.model.grid.query.CellValueFilter\",\"columnName\":\"favoriteFoods\",\"operator\":\"IS_NOT_NULL\"}]}";
		event = new ReturnControlEvent(1L, "group", "function", null, List.of(new Parameter("update", "object", json)),
				new GridAgentSessionContext().setGridSessionId(gridSessionId).setUsersReplicaId(usersReplicaId)
						.setAgentsReplicaId(agentsReplicaId));

		GridConnectionInfo internalConn = new GridConnectionInfo().setReplicaId(11L).setSessionId(gridSessionId)
				.setConnectionId("int-1").setSource(EventSource.INTERNAL);
		when(mockGridManager.getSingletonConnection(gridSessionId, EventSource.INTERNAL))
				.thenReturn(Optional.of(internalConn));
		GridConnectionInfo agentConn = new GridConnectionInfo().setReplicaId(agentsReplicaId)
				.setSessionId(gridSessionId).setConnectionId("agent-1").setSource(EventSource.AGENT);
		when(mockGridManager.getConnection(gridSessionId, agentsReplicaId)).thenReturn(Optional.of(agentConn));
		GridHeader header = buildHeader(List.of(new Column().setName("lastFed").setVectorIndex(0),
				new Column().setName("colB").setVectorIndex(1)));
		when(mockGridViewManager.readHeader(gridSessionId, internalConn.getReplicaId(), usersReplicaId))
				.thenReturn(Optional.of(header));
		List<RowView> rows = List.of(buildRow(1L, 100L), buildRow(1L, 101L));
		when(mockGridViewManager.getQueryIterator(eq(header), any(QueryElement.class))).thenReturn(rows.iterator());
		doReturn(mockIntendedChangePublisher).when(handler).newIntendedChangePublisher(agentConn,
				header.getClockSequenceMaximum(), mockPatchBuilderPublisher);

		// call under test
		String result = handler.handleEvent(event);

		assertEquals("{\"rowsUpdated\":2}", result);
		verify(mockIntendedChangePublisher, times(2)).publish(any());
		verify(mockIntendedChangePublisher).close();

	}
}