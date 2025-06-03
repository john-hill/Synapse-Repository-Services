package org.sagebionetworks.repo.model.grid;

import java.util.Date;
import java.util.Objects;

public class ConnectionInfo {

	private String connectionId;
	private String sessionId;
	private Long replciaId;
	private Long createdBy;
	private Date createdOn;
	private EventSource source;

	public String getConnectionId() {
		return connectionId;
	}

	public ConnectionInfo setConnectionId(String connectionId) {
		this.connectionId = connectionId;
		return this;
	}

	public String getSessionId() {
		return sessionId;
	}

	public ConnectionInfo setSessionId(String sessionId) {
		this.sessionId = sessionId;
		return this;
	}

	public Long getReplciaId() {
		return replciaId;
	}

	public ConnectionInfo setReplciaId(Long replciaId) {
		this.replciaId = replciaId;
		return this;
	}

	public Long getCreatedBy() {
		return createdBy;
	}

	public ConnectionInfo setCreatedBy(Long createdBy) {
		this.createdBy = createdBy;
		return this;
	}

	public Date getCreatedOn() {
		return createdOn;
	}

	public ConnectionInfo setCreatedOn(Date createdOn) {
		this.createdOn = createdOn;
		return this;
	}

	public EventSource getSource() {
		return source;
	}

	public ConnectionInfo setSource(EventSource source) {
		this.source = source;
		return this;
	}

	@Override
	public int hashCode() {
		return Objects.hash(connectionId, createdBy, createdOn, replciaId, sessionId, source);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		ConnectionInfo other = (ConnectionInfo) obj;
		return Objects.equals(connectionId, other.connectionId) && Objects.equals(createdBy, other.createdBy)
				&& Objects.equals(createdOn, other.createdOn) && Objects.equals(replciaId, other.replciaId)
				&& Objects.equals(sessionId, other.sessionId) && source == other.source;
	}

	@Override
	public String toString() {
		return "ConnectionInfo [connectionId=" + connectionId + ", sessionId=" + sessionId + ", replciaId=" + replciaId
				+ ", createdBy=" + createdBy + ", createdOn=" + createdOn + ", source=" + source + "]";
	}

}
