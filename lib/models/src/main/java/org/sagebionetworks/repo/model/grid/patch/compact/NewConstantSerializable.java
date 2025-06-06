package org.sagebionetworks.repo.model.grid.patch.compact;

import org.json.JSONArray;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;
import org.sagebionetworks.repo.model.grid.patch.operation.NewConstant;
import org.sagebionetworks.repo.model.grid.patch.operation.OperationType;

public class NewConstantSerializable implements OperationSerializable<NewConstant> {

	@Override
	public NewConstant deserialize(LogicalTimestamp patchId, int index, JSONArray array) {
		LogicalTimestamp id = new LogicalTimestamp().setReplicaId(patchId.getReplicaId())
				.setSequenceNumber(patchId.getSequenceNumber() + index);
		NewConstant con = new NewConstant().setId(id);
		if (array.length() > 1) {
			con.setValue(array.get(1));
		}
		if (array.length() > 2) {
			con.setTimestamp(array.getBoolean(2));
		}
		return con;
	}

	@Override
	public JSONArray serialize(LogicalTimestamp patchId, int index, NewConstant opp) {
		JSONArray array = new JSONArray().put(OperationType.new_con.getCode());
		if (opp.isTimestamp()) {
			JSONArray arrayValue = (JSONArray) opp.getValue();
			if (patchId.getSequenceNumber() == arrayValue.get(0)) {
				array.put(new JSONArray().put(arrayValue.get(1)));
			}
		} else if (opp.getValue() != null) {
			array.put(opp.getValue());
		}
		return array;
	}

	@Override
	public OperationType getType() {
		return OperationType.new_con;
	}

	@Override
	public Class<? extends NewConstant> getTypeClass() {
		return NewConstant.class;
	}

}
