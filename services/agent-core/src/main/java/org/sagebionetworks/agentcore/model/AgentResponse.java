package org.sagebionetworks.agentcore.model;

import java.util.List;
import java.util.Map;

public class AgentResponse {

	private String output;
	private String sessionId;
	private List<String> citations;
	private Map<String, Object> trace;

	public String getOutput() {
		return output;
	}

	public void setOutput(String output) {
		this.output = output;
	}

	public String getSessionId() {
		return sessionId;
	}

	public void setSessionId(String sessionId) {
		this.sessionId = sessionId;
	}

	public List<String> getCitations() {
		return citations;
	}

	public void setCitations(List<String> citations) {
		this.citations = citations;
	}

	public Map<String, Object> getTrace() {
		return trace;
	}

	public void setTrace(Map<String, Object> trace) {
		this.trace = trace;
	}

}
