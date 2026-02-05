package org.sagebionetworks.repo.manager.grid.synch;

import java.util.Objects;

import org.sagebionetworks.repo.model.grid.patch.ConValue;

public class CopyCell {

	private String name;
	private ConValue value;
	private boolean wasChangedByUser;

	public String getName() {
		return name;
	}

	public CopyCell setName(String name) {
		this.name = name;
		return this;
	}

	public ConValue getValue() {
		return value;
	}

	public CopyCell setValue(ConValue value) {
		this.value = value;
		return this;
	}

	public boolean isWasChangedByUser() {
		return wasChangedByUser;
	}

	public CopyCell setWasChangedByUser(boolean wasChangedByUser) {
		this.wasChangedByUser = wasChangedByUser;
		return this;
	}

	@Override
	public int hashCode() {
		return Objects.hash(name, value, wasChangedByUser);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		CopyCell other = (CopyCell) obj;
		return Objects.equals(name, other.name) && Objects.equals(value, other.value)
				&& wasChangedByUser == other.wasChangedByUser;
	}

	@Override
	public String toString() {
		return "CopyCell [name=" + name + ", value=" + value + ", wasChangedByUser=" + wasChangedByUser + "]";
	}

}
