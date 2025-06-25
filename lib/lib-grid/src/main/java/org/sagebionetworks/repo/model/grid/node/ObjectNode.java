package org.sagebionetworks.repo.model.grid.node;

import java.util.Map;
import java.util.Objects;

import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;

public class ObjectNode implements Node {

	private LogicalTimestamp id;
	private Map<String, LogicalTimestamp> map;

	@Override
	public LogicalTimestamp getId() {
		return id;
	}

	public Map<String, LogicalTimestamp> getMap() {
		return map;
	}

	public ObjectNode setMap(Map<String, LogicalTimestamp> map) {
		this.map = map;
		return this;
	}

	public ObjectNode setId(LogicalTimestamp id) {
		this.id = id;
		return this;
	}

	@Override
	public int hashCode() {
		return Objects.hash(id, map);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		ObjectNode other = (ObjectNode) obj;
		return Objects.equals(id, other.id) && Objects.equals(map, other.map);
	}

	@Override
	public String toString() {
		return "ObjectNode [id=" + id + ", map=" + map + "]";
	}

}
