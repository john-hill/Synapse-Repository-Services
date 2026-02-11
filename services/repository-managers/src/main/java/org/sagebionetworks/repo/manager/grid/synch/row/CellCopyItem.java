package org.sagebionetworks.repo.manager.grid.synch.row;

import java.util.Objects;

import org.sagebionetworks.repo.manager.grid.synch.core.CopyItem;
import org.sagebionetworks.repo.model.grid.patch.ConValue;

/**
 * Represents a single cell from a copy row during Phase 2 cell-level
 * synchronization. Provides the cell's value and tracks whether the user
 * modified this cell, enabling conflict resolution during row merging.
 *
 * <p>
 * During row synchronization, when a copy row and source row don't match
 * (different hashes), the {@link RowMerge} logic compares cells individually to
 * determine:
 * <ul>
 * <li>Which cells changed in the copy vs. source</li>
 * <li>Which changes came from the user vs. external source updates</li>
 * <li>How to resolve conflicts when both copy and source changed the same
 * cell</li>
 * </ul>
 *
 * <p>
 * The {@code wasChangedByUser} flag is critical for conflict resolution:
 * <ul>
 * <li>User changes are pushed to the source (user intent preserved)</li>
 * <li>External source changes are pulled to the copy (source updates
 * synced)</li>
 * <li>When both changed, user changes take precedence</li>
 * </ul>
 */
public class CellCopyItem implements CopyItem {

	private String name;
	private ConValue value;
	private boolean wasChangedByUser;

	/**
	 * Gets the column name for this cell. Used to match cells between copy and
	 * source rows during cell-level comparison.
	 *
	 * @return the column name
	 */
	public String getName() {
		return name;
	}

	/**
	 * Sets the column name for this cell.
	 *
	 * @param name the column name
	 * @return this instance for method chaining
	 */
	public CellCopyItem setName(String name) {
		this.name = name;
		return this;
	}

	/**
	 * Gets the cell's value from the copy. This value is compared with the source
	 * cell value during cell-level synchronization to detect changes.
	 *
	 * @return the cell value
	 */
	public ConValue getValue() {
		return value;
	}

	/**
	 * Sets the cell's value.
	 *
	 * @param value the cell value
	 * @return this instance for method chaining
	 */
	public CellCopyItem setValue(ConValue value) {
		this.value = value;
		return this;
	}

	/**
	 * Indicates whether this cell was modified by the user in the copy. Used during
	 * cell-level conflict resolution to determine whether to push this change to
	 * the source or pull the source's value to the copy.
	 *
	 * <p>
	 * During synchronization:
	 * <ul>
	 * <li>If {@code true} and source cell differs: push user's change to
	 * source</li>
	 * <li>If {@code false} and source cell differs: pull source's change to
	 * copy</li>
	 * <li>If both copy and source changed: user change takes precedence (pushed to
	 * source)</li>
	 * </ul>
	 *
	 * @return true if the user modified this cell, false if it was externally
	 *         changed or unchanged
	 */
	@Override
	public boolean wasChangedByUser() {
		return wasChangedByUser;
	}

	/**
	 * Sets whether this cell was modified by the user.
	 *
	 * @param wasChangedByUser true if user modified the cell, false otherwise
	 * @return this instance for method chaining
	 */
	public CellCopyItem setWasChangedByUser(boolean wasChangedByUser) {
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
		CellCopyItem other = (CellCopyItem) obj;
		return Objects.equals(name, other.name) && Objects.equals(value, other.value)
				&& wasChangedByUser == other.wasChangedByUser;
	}

	@Override
	public String toString() {
		return "Cell [name=" + name + ", value=" + value + ", wasChangedByUser=" + wasChangedByUser + "]";
	}

}
