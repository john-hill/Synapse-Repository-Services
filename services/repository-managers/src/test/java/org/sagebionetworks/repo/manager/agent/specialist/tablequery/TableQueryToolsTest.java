package org.sagebionetworks.repo.manager.agent.specialist.tablequery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.manager.agent.specialist.ToolResponse;
import org.sagebionetworks.repo.manager.table.TableManagerSupport;
import org.sagebionetworks.repo.manager.table.TableQueryManager;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.agent.TableDescription;
import org.sagebionetworks.repo.model.dao.table.TableType;
import org.sagebionetworks.repo.model.entity.IdAndVersion;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.repo.model.table.Query;
import org.sagebionetworks.repo.model.table.QueryOptions;
import org.sagebionetworks.repo.model.table.QueryResult;
import org.sagebionetworks.repo.model.table.QueryResultBundle;
import org.sagebionetworks.repo.model.table.Row;
import org.sagebionetworks.repo.model.table.RowSet;
import org.sagebionetworks.repo.model.table.SelectColumn;
import org.sagebionetworks.util.progress.ProgressCallback;
import org.springaicommunity.agentcore.codeinterpreter.AgentCoreCodeInterpreterClient;
import org.springaicommunity.agentcore.codeinterpreter.CodeExecutionResult;
import org.springframework.ai.chat.model.ToolContext;

@ExtendWith(MockitoExtension.class)
public class TableQueryToolsTest {

	@Mock
	private TableQueryManager mockTableQueryManager;

	@Mock
	private TableManagerSupport mockTableManagerSupport;

	@Mock
	private AgentCoreCodeInterpreterClient mockCodeInterpreterClient;

	private TableQueryTools tools;
	private UserInfo userInfo;
	private ToolContext toolContext;
	private ToolContext toolContextWithSession;

	@BeforeEach
	public void setup() {
		tools = new TableQueryTools(mockTableQueryManager, mockTableManagerSupport, mockCodeInterpreterClient);
		userInfo = new UserInfo(false, 101L);
		toolContext = new ToolContext(Map.of("userInfo", userInfo));
		toolContextWithSession = new ToolContext(Map.of("userInfo", userInfo, "sessionId", "session-123"));
	}

	@Test
	public void testDescribeTableWithValidId() {
		IdAndVersion idAndVersion = IdAndVersion.parse("syn123");
		ColumnModel col1 = new ColumnModel();
		col1.setName("name");
		col1.setColumnType(ColumnType.STRING);
		ColumnModel col2 = new ColumnModel();
		col2.setName("age");
		col2.setColumnType(ColumnType.INTEGER);

		when(mockTableManagerSupport.getTableType(idAndVersion)).thenReturn(TableType.entityview);
		when(mockTableManagerSupport.getTableSchema(idAndVersion)).thenReturn(List.of(col1, col2));

		// call under test
		ToolResponse<TableDescription> response = tools.describeTable("syn123", toolContext);

		assertNotNull(response.getResponseBody());
		assertNull(response.getErrorMessage());
		assertEquals("syn123", response.getResponseBody().getTableId());
		assertEquals("entityview", response.getResponseBody().getTableType());
		assertEquals(2, response.getResponseBody().getColumnModels().size());
		assertEquals("name", response.getResponseBody().getColumnModels().get(0).getName());
		assertEquals(ColumnType.STRING, response.getResponseBody().getColumnModels().get(0).getColumnType());
		assertEquals("age", response.getResponseBody().getColumnModels().get(1).getName());
		assertEquals(ColumnType.INTEGER, response.getResponseBody().getColumnModels().get(1).getColumnType());
	}

	@Test
	public void testDescribeTableWithInvalidId() {
		// call under test
		ToolResponse<TableDescription> response = tools.describeTable("not_a_valid_id!", toolContext);

		assertNull(response.getResponseBody());
		assertNotNull(response.getErrorMessage());
		verifyNoInteractions(mockTableManagerSupport);
	}

	@Test
	public void testDescribeTableWithNoUserInfo() {
		ToolContext noUserContext = new ToolContext(Map.of());

		// call under test
		ToolResponse<TableDescription> response = tools.describeTable("syn123", noUserContext);

		assertNull(response.getResponseBody());
		assertEquals("No user context available", response.getErrorMessage());
		verifyNoInteractions(mockTableManagerSupport);
	}

	@Test
	public void testQueryTableWithResults() throws Exception {
		SelectColumn sc1 = new SelectColumn();
		sc1.setName("name");
		SelectColumn sc2 = new SelectColumn();
		sc2.setName("score");

		Row row1 = new Row();
		row1.setValues(List.of("Alice", "95.5"));
		Row row2 = new Row();
		row2.setValues(List.of("Bob", "87.3"));

		RowSet rowSet = new RowSet();
		rowSet.setRows(List.of(row1, row2));
		QueryResult queryResult = new QueryResult();
		queryResult.setQueryResults(rowSet);

		QueryResultBundle bundle = new QueryResultBundle();
		bundle.setQueryResult(queryResult);
		bundle.setSelectColumns(List.of(sc1, sc2));
		bundle.setQueryCount(2L);

		when(mockTableQueryManager.querySinglePage(any(ProgressCallback.class), eq(userInfo), any(Query.class), any(QueryOptions.class)))
				.thenReturn(bundle);

		// call under test
		ToolResponse<QueryResultBundle> response = tools.queryTable("SELECT name, score FROM syn123", null, toolContext);

		assertNotNull(response.getResponseBody());
		assertNull(response.getErrorMessage());
		assertEquals(2L, response.getResponseBody().getQueryCount());
		assertEquals(2, response.getResponseBody().getQueryResult().getQueryResults().getRows().size());

		ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
		verify(mockTableQueryManager).querySinglePage(any(), eq(userInfo), queryCaptor.capture(), any());
		assertEquals("SELECT name, score FROM syn123", queryCaptor.getValue().getSql());
		assertEquals(100L, queryCaptor.getValue().getLimit());
		assertEquals(0L, queryCaptor.getValue().getOffset());
	}

	@Test
	public void testQueryTableWithLimitCap() throws Exception {
		QueryResultBundle bundle = new QueryResultBundle();
		when(mockTableQueryManager.querySinglePage(any(ProgressCallback.class), eq(userInfo), any(Query.class), any(QueryOptions.class)))
				.thenReturn(bundle);

		// call under test
		tools.queryTable("SELECT * FROM syn123", 500L, toolContext);

		ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
		verify(mockTableQueryManager).querySinglePage(any(), eq(userInfo), queryCaptor.capture(), any());
		assertEquals(100L, queryCaptor.getValue().getLimit());
	}

	@Test
	public void testQueryTableWithSmallLimit() throws Exception {
		QueryResultBundle bundle = new QueryResultBundle();
		when(mockTableQueryManager.querySinglePage(any(ProgressCallback.class), eq(userInfo), any(Query.class), any(QueryOptions.class)))
				.thenReturn(bundle);

		// call under test
		tools.queryTable("SELECT * FROM syn123", 5L, toolContext);

		ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
		verify(mockTableQueryManager).querySinglePage(any(), eq(userInfo), queryCaptor.capture(), any());
		assertEquals(5L, queryCaptor.getValue().getLimit());
	}

	@Test
	public void testQueryTableWithNoUserInfo() {
		ToolContext noUserContext = new ToolContext(Map.of());

		// call under test
		ToolResponse<QueryResultBundle> response = tools.queryTable("SELECT * FROM syn123", null, noUserContext);

		assertNull(response.getResponseBody());
		assertEquals("No user context available", response.getErrorMessage());
		verifyNoInteractions(mockTableQueryManager);
	}

	@Test
	public void testQueryTableWithException() throws Exception {
		when(mockTableQueryManager.querySinglePage(any(ProgressCallback.class), eq(userInfo), any(Query.class), any(QueryOptions.class)))
				.thenThrow(new RuntimeException("Table not available"));

		// call under test
		ToolResponse<QueryResultBundle> response = tools.queryTable("SELECT * FROM syn999", null, toolContext);

		assertNull(response.getResponseBody());
		assertEquals("Error executing query: Table not available", response.getErrorMessage());
	}

	@Test
	public void testWriteQueryToSessionWithResults() throws Exception {
		CodeExecutionResult successResult = new CodeExecutionResult("done", false, List.of());
		when(mockCodeInterpreterClient.executeCode(eq("session-123"), eq("python"), any(String.class)))
				.thenReturn(successResult);

		QueryResultBundle metadata = new QueryResultBundle();
		metadata.setQueryCount(50L);
		when(mockTableQueryManager.querySinglePage(any(ProgressCallback.class), eq(userInfo), any(Query.class), any(QueryOptions.class)))
				.thenReturn(metadata);

		// call under test
		ToolResponse<QueryResultBundle> response = tools.writeQueryToSession(
				"SELECT * FROM syn123", "query_specialist/out.csv", toolContextWithSession);

		assertNotNull(response.getResponseBody());
		assertNull(response.getErrorMessage());
		assertEquals(50L, response.getResponseBody().getQueryCount());
	}

	@Test
	public void testWriteQueryToSessionWithNoUserInfo() {
		ToolContext noUserContext = new ToolContext(Map.of("sessionId", "session-123"));

		// call under test
		ToolResponse<QueryResultBundle> response = tools.writeQueryToSession(
				"SELECT * FROM syn123", "out.csv", noUserContext);

		assertNull(response.getResponseBody());
		assertEquals("No user context available", response.getErrorMessage());
		verifyNoInteractions(mockCodeInterpreterClient);
	}

	@Test
	public void testWriteQueryToSessionWithNoSessionId() {
		// call under test
		ToolResponse<QueryResultBundle> response = tools.writeQueryToSession(
				"SELECT * FROM syn123", "out.csv", toolContext);

		assertNull(response.getResponseBody());
		assertEquals("No code interpreter session ID available", response.getErrorMessage());
		verifyNoInteractions(mockCodeInterpreterClient);
	}

	@Test
	public void testWriteQueryToSessionWithWriteError() throws Exception {
		CodeExecutionResult ensureDirResult = new CodeExecutionResult("", false, List.of());
		CodeExecutionResult writeErrorResult = new CodeExecutionResult("Permission denied", true, List.of());
		when(mockCodeInterpreterClient.executeCode(eq("session-123"), eq("python"), any(String.class)))
				.thenReturn(ensureDirResult, writeErrorResult);

		// call under test
		ToolResponse<QueryResultBundle> response = tools.writeQueryToSession(
				"SELECT * FROM syn123", "query_specialist/out.csv", toolContextWithSession);

		assertNull(response.getResponseBody());
		assertNotNull(response.getErrorMessage());
	}

	@Test
	public void testEscapeCsvField() {
		assertEquals("simple", TableQueryTools.escapeCsvField("simple"));
		assertEquals("", TableQueryTools.escapeCsvField(null));
		assertEquals("\"has,comma\"", TableQueryTools.escapeCsvField("has,comma"));
		assertEquals("\"has\"\"quote\"", TableQueryTools.escapeCsvField("has\"quote"));
		assertEquals("\"has\nnewline\"", TableQueryTools.escapeCsvField("has\nnewline"));
	}

	@Test
	public void testEscapePythonTripleQuote() {
		assertEquals("no quotes", TableQueryTools.escapePythonTripleQuote("no quotes"));
		assertEquals("has\\'\\'\\' quotes", TableQueryTools.escapePythonTripleQuote("has''' quotes"));
		assertEquals("back\\\\slash", TableQueryTools.escapePythonTripleQuote("back\\slash"));
	}
}
