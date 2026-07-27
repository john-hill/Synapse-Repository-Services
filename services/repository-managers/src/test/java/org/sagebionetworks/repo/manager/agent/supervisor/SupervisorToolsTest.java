package org.sagebionetworks.repo.manager.agent.supervisor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.manager.agent.specialist.entitymetadata.EntityMetadataSpecialist;
import org.sagebionetworks.repo.manager.agent.specialist.entitymetadata.EntityMetadataSpecialistFactory;
import org.sagebionetworks.repo.manager.agent.specialist.filesummary.FileSummarySpecialist;
import org.sagebionetworks.repo.manager.agent.specialist.filesummary.FileSummarySpecialistFactory;
import org.sagebionetworks.repo.manager.agent.specialist.gridquery.GridQuerySpecialist;
import org.sagebionetworks.repo.manager.agent.specialist.gridquery.GridQuerySpecialistFactory;
import org.sagebionetworks.repo.manager.agent.specialist.gridupdate.GridUpdateSpecialist;
import org.sagebionetworks.repo.manager.agent.specialist.gridupdate.GridUpdateSpecialistFactory;
import org.sagebionetworks.repo.manager.agent.specialist.jsonschema.JsonSchemaSpecialist;
import org.sagebionetworks.repo.manager.agent.specialist.jsonschema.JsonSchemaSpecialistFactory;
import org.sagebionetworks.repo.manager.agent.specialist.tablequery.TableQuerySpecialist;
import org.sagebionetworks.repo.manager.agent.specialist.tablequery.TableQuerySpecialistFactory;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.agent.GridAgentSessionContext;
import org.springframework.ai.chat.model.ToolContext;

@ExtendWith(MockitoExtension.class)
public class SupervisorToolsTest {

	@Mock
	private TableQuerySpecialistFactory tableQuerySpecialistFactory;
	@Mock
	private JsonSchemaSpecialistFactory jsonSchemaSpecialistFactory;
	@Mock
	private FileSummarySpecialistFactory fileSummarySpecialistFactory;
	@Mock
	private EntityMetadataSpecialistFactory entityMetadataSpecialistFactory;
	@Mock
	private GridQuerySpecialistFactory gridQuerySpecialistFactory;
	@Mock
	private GridUpdateSpecialistFactory gridUpdateSpecialistFactory;

	@Mock
	private TableQuerySpecialist tableQuerySpecialist;
	@Mock
	private JsonSchemaSpecialist jsonSchemaSpecialist;
	@Mock
	private FileSummarySpecialist fileSummarySpecialist;
	@Mock
	private EntityMetadataSpecialist entityMetadataSpecialist;
	@Mock
	private GridQuerySpecialist gridQuerySpecialist;
	@Mock
	private GridUpdateSpecialist gridUpdateSpecialist;

	private SupervisorTools tools;
	private UserInfo userInfo;
	private GridAgentSessionContext gridContext;
	private ToolContext toolContext;

	@BeforeEach
	public void setup() {
		tools = new SupervisorTools(tableQuerySpecialistFactory, jsonSchemaSpecialistFactory, fileSummarySpecialistFactory,
				entityMetadataSpecialistFactory, gridQuerySpecialistFactory, gridUpdateSpecialistFactory);
		userInfo = new UserInfo(false, 101L);
		gridContext = new GridAgentSessionContext().setGridSessionId("grid-1").setUsersReplicaId(1L)
				.setAgentsReplicaId(2L);
		toolContext = new ToolContext(
				Map.of("userInfo", userInfo, "sessionId", "session-123", "gridAgentSessionContext", gridContext));
	}

	@Test
	public void testAskTableQuerySpecialist() {
		when(tableQuerySpecialistFactory.create()).thenReturn(tableQuerySpecialist);
		when(tableQuerySpecialist.chat("describe syn1", userInfo, "session-123")).thenReturn("table described");

		// call under test
		String result = tools.askTableQuerySpecialist("describe syn1", toolContext);

		assertEquals("table described", result);
		// A fresh specialist is created and given the propagated user + session.
		verify(tableQuerySpecialistFactory).create();
		verify(tableQuerySpecialist).chat("describe syn1", userInfo, "session-123");
	}

	@Test
	public void testAskJsonSchemaSpecialist() {
		when(jsonSchemaSpecialistFactory.create()).thenReturn(jsonSchemaSpecialist);
		when(jsonSchemaSpecialist.chat("describe my.org-S", userInfo, "session-123")).thenReturn("schema described");

		// call under test
		String result = tools.askJsonSchemaSpecialist("describe my.org-S", toolContext);

		assertEquals("schema described", result);
		verify(jsonSchemaSpecialistFactory).create();
		verify(jsonSchemaSpecialist).chat("describe my.org-S", userInfo, "session-123");
	}

	@Test
	public void testAskFileSummarySpecialist() {
		when(fileSummarySpecialistFactory.create()).thenReturn(fileSummarySpecialist);
		when(fileSummarySpecialist.chat("summarize out.csv", userInfo, "session-123")).thenReturn("file summarized");

		// call under test
		String result = tools.askFileSummarySpecialist("summarize out.csv", toolContext);

		assertEquals("file summarized", result);
		verify(fileSummarySpecialistFactory).create();
		verify(fileSummarySpecialist).chat("summarize out.csv", userInfo, "session-123");
	}

	@Test
	public void testAskEntityMetadataSpecialist() {
		when(entityMetadataSpecialistFactory.create()).thenReturn(entityMetadataSpecialist);
		when(entityMetadataSpecialist.chat("annotations of syn1", userInfo, "session-123")).thenReturn("annotations described");

		// call under test
		String result = tools.askEntityMetadataSpecialist("annotations of syn1", toolContext);

		assertEquals("annotations described", result);
		verify(entityMetadataSpecialistFactory).create();
		verify(entityMetadataSpecialist).chat("annotations of syn1", userInfo, "session-123");
	}

	@Test
	public void testAskGridQuerySpecialist() {
		when(gridQuerySpecialistFactory.create()).thenReturn(gridQuerySpecialist);
		when(gridQuerySpecialist.chat("count the rows", userInfo, "session-123", gridContext))
				.thenReturn("grid queried");

		// call under test
		String result = tools.askGridQuerySpecialist("count the rows", toolContext);

		assertEquals("grid queried", result);
		// A fresh specialist is created and given the propagated user, session, and grid context.
		verify(gridQuerySpecialistFactory).create();
		verify(gridQuerySpecialist).chat("count the rows", userInfo, "session-123", gridContext);
	}

	@Test
	public void testAskGridUpdateSpecialist() {
		when(gridUpdateSpecialistFactory.create()).thenReturn(gridUpdateSpecialist);
		when(gridUpdateSpecialist.chat("set age to 25", userInfo, "session-123", gridContext))
				.thenReturn("grid updated");

		// call under test
		String result = tools.askGridUpdateSpecialist("set age to 25", toolContext);

		assertEquals("grid updated", result);
		verify(gridUpdateSpecialistFactory).create();
		verify(gridUpdateSpecialist).chat("set age to 25", userInfo, "session-123", gridContext);
	}

	@Test
	public void testAskTableQuerySpecialistWithoutSessionId() {
		ToolContext noSession = new ToolContext(Map.of("userInfo", userInfo));
		when(tableQuerySpecialistFactory.create()).thenReturn(tableQuerySpecialist);
		when(tableQuerySpecialist.chat("describe syn1", userInfo, null)).thenReturn("ok");

		// call under test — a null sessionId is forwarded as-is
		String result = tools.askTableQuerySpecialist("describe syn1", noSession);

		assertEquals("ok", result);
		verify(tableQuerySpecialist).chat("describe syn1", userInfo, null);
	}
}
