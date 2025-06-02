package org.sagebionetworks.grid.workers.message.builder;

import java.util.Optional;
import java.util.StringJoiner;

import org.sagebionetworks.repo.model.grid.EventContext;
import org.sagebionetworks.repo.model.grid.event.JsonRxMessageType;

public interface JsonRxMessageBuilder {

	/**
	 * The input JSON-Rx type that matches the message built by this builder.
	 * 
	 * @return
	 */
	JsonRxMessageType type();

	/**
	 * The input method name that matches the message build by this builder. Return
	 * {@link Optional#empty()} if this message does not have a method name.
	 * 
	 * @return
	 */
	Optional<String> method();

	/**
	 * The key used to match this builder to a message.
	 * 
	 * @return
	 */
	default String typeKey() {
		return createTypeKey(type(), method().orElse(""));
	}

	/**
	 * Build a message fo this type.
	 * 
	 * @param context
	 * @param id
	 * @param body
	 * @return Message object.
	 */
	Object build(EventContext context, Integer id, Object body);
	
	/**
	 * Helper to create a key from the type and method.
	 * @param type
	 * @param method
	 * @return
	 */
	public static String createTypeKey(JsonRxMessageType type, String method) {
		return new StringJoiner("#").add(type.name()).add(method).toString();
	}

}
