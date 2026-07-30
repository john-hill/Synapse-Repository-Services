package org.sagebionetworks.agent.worker;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.manager.agent.supervisor.CurieSupervisor;
import org.sagebionetworks.repo.manager.agent.supervisor.CurieSupervisorFactory;
import org.sagebionetworks.repo.model.AuthorizationConstants.BOOTSTRAP_PRINCIPAL;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.agent.GridAgentSessionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Verifies that Curie's conversation memory is durable across supervisor instances. Curie is invoked
 * from asynchronous chat jobs, so consecutive turns of one conversation may run on different worker
 * machines — each turn builds a fresh {@link CurieSupervisor} (a new ChatClient and a new windowing
 * memory) from the factory. Memory must therefore live in the shared, durable
 * {@code ChatMemoryRepository} (Bedrock AgentCore Memory), keyed by the user and the durable chat
 * {@code sessionId}, not in any per-instance store. Requires live Bedrock + AgentCore Memory access.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = { "classpath:test-context.xml" })
public class CurieSupervisorIntegrationTest {

	@Autowired
	private UserManager userManager;

	@Autowired
	private CurieSupervisorFactory curieSupervisorFactory;

	private UserInfo adminUser;

	@BeforeEach
	public void setup() {
		adminUser = userManager.getUserInfo(BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId());
	}

	@Test
	public void testMemoryPersistsAcrossSupervisorInstances() {
		// A durable session id shared by both turns; unique per run so repeated runs never collide.
		String sessionId = "curieMemoryIT-" + UUID.randomUUID();
		GridAgentSessionContext gridContext = new GridAgentSessionContext()
				.setGridSessionId(sessionId)
				.setUsersReplicaId(1L);
		String referenceCode = "ZEBRA-4417";

		// First turn on the first supervisor: establish a fact only this conversation could know.
		CurieSupervisor firstTurn = curieSupervisorFactory.create();
		String ack = firstTurn.chat(
				"Please remember this reference code for our session: " + referenceCode
						+ ". Just confirm you have noted it.",
				adminUser, sessionId, gridContext, null);
		assertNotNull(ack);

		// A brand-new supervisor for the second turn — as if this turn ran on a different worker machine.
		CurieSupervisor secondTurn = curieSupervisorFactory.create();
		assertNotSame(firstTurn, secondTurn, "The factory must produce a distinct supervisor instance");

		// call under test — the second, independently built supervisor recalls the first turn's fact,
		// which is only possible if the memory was loaded from the shared durable store by the
		// user:sessionId conversation key.
		String recall = secondTurn.chat(
				"What reference code did I give you earlier in this session? Reply with only the code.",
				adminUser, sessionId, gridContext, null);
		assertNotNull(recall);
		assertTrue(recall.toUpperCase().contains(referenceCode),
				"A new supervisor should recall the earlier turn's reference code. Got: " + recall);
	}

	@Test
	public void testMemoryIsolatedBySession() {
		String referenceCode = "ZEBRA-4417";

		// Establish the fact under one session.
		String establishedSessionId = "curieMemoryIT-" + UUID.randomUUID();
		GridAgentSessionContext establishedContext = new GridAgentSessionContext()
				.setGridSessionId(establishedSessionId)
				.setUsersReplicaId(1L);
		curieSupervisorFactory.create().chat(
				"Please remember this reference code for our session: " + referenceCode
						+ ". Just confirm you have noted it.",
				adminUser, establishedSessionId, establishedContext, null);

		// A different session for the same user must not see it — proving memory is keyed by session,
		// not shared globally across the user's conversations.
		String otherSessionId = "curieMemoryIT-" + UUID.randomUUID();
		GridAgentSessionContext otherContext = new GridAgentSessionContext()
				.setGridSessionId(otherSessionId)
				.setUsersReplicaId(1L);

		// call under test
		String recall = curieSupervisorFactory.create().chat(
				"What reference code did I give you earlier in this session? "
						+ "If you have no reference code on record, reply exactly NONE.",
				adminUser, otherSessionId, otherContext, null);
		assertNotNull(recall);
		assertFalse(recall.toUpperCase().contains(referenceCode),
				"A separate session must not recall another session's reference code. Got: " + recall);
	}
}
