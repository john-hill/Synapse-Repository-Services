package org.sagebionetworks.repo.manager.agent.handler;

public interface OpenApiReturnControlHandler extends ReturnControlHandler {

	default public String getFunction() {
		return String.format("%s %s", getHttpMethod().name().toUpperCase(), getPath());
	}

	/**
	 * The path defining this function in the Open API schema.
	 * 
	 * @return
	 */
	String getPath();

	/**
	 * The http method defining this function in the Open API schema.
	 * 
	 * @return
	 */
	HttpMethod getHttpMethod();

	/**
	 * The response code that defines a successful response to a call to this
	 * function. This should match the code used to define this function's response
	 * in the Open API schema.
	 * 
	 * @return
	 */
	HttpCode getSuccessHttpCode();
}
