package org.sagebionetworks.repo.model.grid;

/**
 * Abstraction for the context of a grid event.
 */
public interface EventContext {
	
	/**
	 * The type of the event.
	 * @return
	 */
	EventType eventType();
	
	/**
	 * The source of the event.
	 * @return
	 */
	EventSource eventSource();

}
