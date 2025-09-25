package org.sagebionetworks.repo.manager.grid.internal.replica.merge;

import java.util.Arrays;

public class JoinedRow {

	private Object[] csvData;
	private Object[] gridData;

	public JoinedRow(Object[] csvData, Object[] gridData) {
		this.csvData = csvData;
		this.gridData = gridData;
	}

	public Object[] getCsvData() {
		return csvData;
	}

	public Object[] getGridData() {
		return gridData;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + Arrays.deepHashCode(csvData);
		result = prime * result + Arrays.deepHashCode(gridData);
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
		if (!Arrays.deepEquals(csvData, other.csvData)) {
			return false;
		}
		if (!Arrays.deepEquals(gridData, other.gridData)) {
			return false;
		}
		return true;
	}

}
