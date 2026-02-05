package org.sagebionetworks.repo.manager.grid.synch.v2;

import java.util.Objects;

import org.sagebionetworks.util.ValidateArgument;

public class ColumnItem implements Item {

	private final String columnName;
	private final boolean wasChangedByUser;

	public ColumnItem(String columnName, boolean wasChangedByUser) {
		ValidateArgument.required(columnName, "columnName");
		this.columnName = columnName;
		this.wasChangedByUser = wasChangedByUser;
	}

	public String getColumnName() {
		return columnName;
	}

	public boolean isWasChangedByUser() {
		return wasChangedByUser;
	}

	@Override
	public boolean wasChangedByUser() {
		return wasChangedByUser;
	}

	@Override
	public boolean matches(Item item) {
		if (this == item)
			return true;
		if (item == null)
			return false;
		if (getClass() != item.getClass())
			return false;
		ColumnItem other = (ColumnItem) item;
		return this.columnName.equals(other.columnName);
	}

	@Override
	public int hashCode() {
		return Objects.hash(columnName, wasChangedByUser);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		ColumnItem other = (ColumnItem) obj;
		return Objects.equals(columnName, other.columnName) && wasChangedByUser == other.wasChangedByUser;
	}

	@Override
	public String toString() {
		return "ColumnItem [columnName=" + columnName + ", wasChangedByUser=" + wasChangedByUser + "]";
	}

}
