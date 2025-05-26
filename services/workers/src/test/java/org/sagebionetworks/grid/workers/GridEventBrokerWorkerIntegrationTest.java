package org.sagebionetworks.grid.workers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = { "classpath:test-context.xml" })
public class GridEventBrokerWorkerIntegrationTest {
	
	@Test
	public void test() throws InterruptedException {
		while(true) {
			System.out.println("Waiting for worker...");
			Thread.sleep(2000L);
		}
	}

}
