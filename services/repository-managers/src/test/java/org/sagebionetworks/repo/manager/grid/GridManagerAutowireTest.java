package org.sagebionetworks.repo.manager.grid;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.repo.model.UserInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = { "classpath:test-context.xml" })
public class GridManagerAutowireTest {

	@Autowired
	private GridManager manager;

	@Test
	public void testPresigned() {
		UserInfo user = new UserInfo(false, 98765L);
		String presigned = manager.createWebsocketPresignedUrl("grid123", 222, user);
		System.out.println(presigned);
		System.out.println("wscat -c '"+presigned+"'");

	}
	
	
	@Test
	public void testSendMessage() {
		String connectionId = "LCSwJfvMIAMCIiw=";
		String message = "This is from the manger demo";
		manager.sendMessage(connectionId, message);
	}

}
