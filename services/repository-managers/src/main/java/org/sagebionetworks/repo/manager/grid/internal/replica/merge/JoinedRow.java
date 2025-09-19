package org.sagebionetworks.repo.manager.grid.internal.replica.merge;

import java.util.Arrays;

import org.json.JSONArray;

public class JoinedRow {
	
	private Object[] upsertKeyValues;
	private JSONArray csvData;
	private JSONArray gridData;
	
	public JoinedRow(Object[] upsertKeyValues, String csvData, String gridData) {
		this.upsertKeyValues = upsertKeyValues;
		this.csvData = csvData == null ? null : new JSONArray(csvData);
		this.gridData = gridData == null ? null : new JSONArray(gridData);
	}
	
	public Object[] getUpsertKeyValues() {
		return upsertKeyValues;
	}
	
	public JSONArray getCsvData() {
		return csvData;
	}
	
	public JSONArray getGridData() {
		return gridData;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((csvData == null) ? 0 : csvData.toString().hashCode());
		result = prime * result + ((gridData == null) ? 0 : gridData.toString().hashCode());
		result = prime * result + Arrays.deepHashCode(upsertKeyValues);
		return result;
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
		JoinedRow other = (JoinedRow) obj;
		if (csvData == null) {
			if (other.csvData != null) {
				return false;
			}
		} else if (!csvData.toString().equals(other.csvData.toString())) {
			return false;
		}
		if (gridData == null) {
			if (other.gridData != null) {
				return false;
			}
		} else if (!gridData.toString().equals(other.gridData.toString())) {
			return false;
		}
		if (!Arrays.deepEquals(upsertKeyValues, other.upsertKeyValues)) {
			return false;
		}
		return true;
	}

	
}
