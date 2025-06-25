package org.sagebionetworks.repo.model.grid.node;

import java.util.List;
import java.util.Objects;

import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;

public class VectorNode implements Node {

	private LogicalTimestamp id;
	private List<ConstantNode> values;

	@Override
	public LogicalTimestamp getId() {
		return id;
	}

	public List<ConstantNode> getValues() {
		return values;
	}

	public VectorNode setValues(List<ConstantNode> values) {
		this.values = values;
		return this;
	}

	public VectorNode setId(LogicalTimestamp id) {
		this.id = id;
		return this;
	}

	@Override
	public int hashCode() {
		return Objects.hash(id, values);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		VectorNode other = (VectorNode) obj;
		return Objects.equals(id, other.id) && Objects.equals(values, other.values);
	}

	@Override
	public String toString() {
		return "VectorNode [id=" + id + ", values=" + values + "]";
	}

}
