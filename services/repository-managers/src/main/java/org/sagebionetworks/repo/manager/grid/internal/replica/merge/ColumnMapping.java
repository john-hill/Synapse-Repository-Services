package org.sagebionetworks.repo.manager.grid.internal.replica.merge;

import java.util.Objects;

import org.sagebionetworks.repo.manager.grid.row.translator.ColumnTypeToConType;
import org.sagebionetworks.repo.manager.grid.row.translator.Translator;
import org.sagebionetworks.repo.model.table.ColumnType;

class ColumnMapping {
	private String columnName;
	private ColumnType type;
	private int sourceIndex;
	private Translator translator;
	private boolean isUpsertColumn;

	ColumnMapping(String columnName, ColumnType type, int sourceIndex, boolean isUpsertColumn) {
		this.columnName = columnName;
		this.type = type;
		this.sourceIndex = sourceIndex;
		this.isUpsertColumn = isUpsertColumn;
		this.translator = type == null ? null : ColumnTypeToConType.lookUpType(type).getTranslator();
	}

	public String getColumnName() {
		return columnName;
	}

	public ColumnType getType() {
		return type;
	}

	public int getSourceIndex() {
		return sourceIndex;
	}

	public Translator getTranslator() {
		return translator;
	}

	public boolean isUpsertColumn() {
		return isUpsertColumn;
	}

	@Override
	public int hashCode() {
		return Objects.hash(columnName, isUpsertColumn, sourceIndex, translator, type);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null) {
			return false;
		}
		if (getClass() != obj.getClass()) {
			return false;
		}
		ColumnMapping other = (ColumnMapping) obj;
		return Objects.equals(columnName, other.columnName) && isUpsertColumn == other.isUpsertColumn && sourceIndex == other.sourceIndex
			&& Objects.equals(translator, other.translator) && type == other.type;
	}

	@Override
	public String toString() {
		return "ColumnMapping [columnName=" + columnName + ", type=" + type + ", sourceIndex=" + sourceIndex + ", translator=" + translator + ", isUpsertColumn=" + isUpsertColumn
			+ "]";
	}

}