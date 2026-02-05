package org.sagebionetworks.repo.manager.grid.synch.v2;

import java.util.List;
import java.util.Objects;

public class RowItem implements Item {

	private String key;
	private List<CellItem> cells;

	public String getKey() {
		return key;
	}

	public RowItem setKey(String key) {
		this.key = key;
		return this;
	}

	public List<CellItem> getCells() {
		return cells;
	}

	public RowItem setCells(List<CellItem> cells) {
		this.cells = cells;
		return this;
	}

	@Override
	public boolean matches(Item item) {
		return equals(item);
	}

	@Override
	public boolean wasChangedByUser() {
		return cells.stream().filter(CellItem::wasChangedByUser).findFirst().isPresent();
	}

	@Override
	public int hashCode() {
		return Objects.hash(cells, key);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		RowItem other = (RowItem) obj;
		return Objects.equals(cells, other.cells) && Objects.equals(key, other.key);
	}

	@Override
	public String toString() {
		return "Row [key=" + key + ", cells=" + cells + "]";
	}

}
