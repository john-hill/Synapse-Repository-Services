package org.sagebionetworks.repo.manager.grid.internal.replica.change;

import org.json.JSONArray;
import org.json.JSONObject;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;
import org.sagebionetworks.repo.model.grid.patch.compact.LogicalTimestampCompactSerializable;
import org.sagebionetworks.util.ValidateArgument;

public class InsertRowChange implements IntendedChange {

	private LogicalTimestamp rowsArrayId; // The id of the rows array to add the row to
	private LogicalTimestamp nodeRefId; // The optional id of the node in the array after which the row should be inserted
	private JSONArray rowData; // The actual row data
	private Integer[] rowVectorIndex; // The vector index of each column in the row data

	public InsertRowChange(LogicalTimestamp rowsArrayId, LogicalTimestamp nodeRefId, JSONArray rowData, Integer[] rowVectorIndex) {
		ValidateArgument.required(rowsArrayId, "rowsArrayId");
		ValidateArgument.required(rowData, "rowData");
		ValidateArgument.required(rowVectorIndex, "rowVectorIndex");
		ValidateArgument.requirement(rowData.length() == rowVectorIndex.length, "rowData and rowVectorIndex must have the same length");
		
		this.rowsArrayId = rowsArrayId;
		this.nodeRefId = nodeRefId;
		this.rowData = rowData;
		this.rowVectorIndex = rowVectorIndex;
	}
	
	public InsertRowChange(JSONObject json) {
		this.rowsArrayId = LogicalTimestampCompactSerializable.deserialize(json.getJSONArray("a"));
		JSONArray nodeRefId = json.optJSONArray("n");
		if (nodeRefId != null) {
			this.nodeRefId = LogicalTimestampCompactSerializable.deserialize(nodeRefId);
		}
		this.rowData = json.getJSONArray("d");
		this.rowVectorIndex = json.getJSONArray("v").toList().stream().map(v -> (Integer)v).toArray(Integer[]::new);
	}

	@Override
	public IntendedChangeType getType() {
		return IntendedChangeType.insert_row;
	}

	@Override
	public JSONObject toJson() {
		JSONObject json = new JSONObject();
		
		json.put("a", LogicalTimestampCompactSerializable.serialize(rowsArrayId));
		
		if (nodeRefId != null) {
			json.put("n", LogicalTimestampCompactSerializable.serialize(nodeRefId));
		}
		
		json.put("d", rowData);
		json.put("v", new JSONArray(rowVectorIndex));
		
		return json;
	}
	
	public LogicalTimestamp getRowsArrayId() {
		return rowsArrayId;
	}
	
	public LogicalTimestamp getNodeRefId() {
		return nodeRefId;
	}
	
	public JSONArray getRowData() {
		return rowData;
	}
	
	public Integer[] getRowVectorIndex() {
		return rowVectorIndex;
	}
	
}
