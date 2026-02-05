package org.sagebionetworks.repo.manager.grid.synch.v2;

import java.util.Objects;

import org.sagebionetworks.repo.model.grid.patch.ConValue;

public class CellItem implements Item {

	private String name;
	private ConValue value;
	private boolean wasChangedByUser;

	public String getName() {
		return name;
	}

	public CellItem setName(String name) {
		this.name = name;
		return this;
	}

	public ConValue getValue() {
		return value;
	}

	public CellItem setValue(ConValue value) {
		this.value = value;
		return this;
	}

	@Override
	public boolean wasChangedByUser() {
		return wasChangedByUser;
	}

	@Override
	public boolean matches(Item obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		CellItem other = (CellItem) obj;
		return Objects.equals(name, other.name) && Objects.equals(value, other.value);
	}

	public CellItem setWasChangedByUser(boolean wasChangedByUser) {
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
		CellItem other = (CellItem) obj;
		return Objects.equals(name, other.name) && Objects.equals(value, other.value)
				&& wasChangedByUser == other.wasChangedByUser;
	}

	@Override
	public String toString() {
		return "Cell [name=" + name + ", value=" + value + ", wasChangedByUser=" + wasChangedByUser + "]";
	}

}
