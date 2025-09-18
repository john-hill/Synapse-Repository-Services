package org.sagebionetworks.repo.manager.grid.internal.replica.view.query;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;
import org.sagebionetworks.util.ValidateArgument;

public class VectorIdFilterElement implements FilterElement {

	private List<Object[]> idTuples;

	public VectorIdFilterElement(List<LogicalTimestamp> vectorIds) {
		ValidateArgument.required(vectorIds, "vectorIds");
		ValidateArgument.requirement(!vectorIds.isEmpty(), "vectorIds cannot be empty");
		idTuples = vectorIds.stream().map(ts -> new Object[] { ts.getReplicaId(), ts.getSequenceNumber() })
				.collect(Collectors.toList());
	}

	@Override
	public void toSql(StringBuilder sqlBuilder, Map<String, Object> params, Context context) {
		int index = params.size();
		String bind = String.format("vectorIds%d", index);
		sqlBuilder.append(String.format(" (VEC_REP, VEC_SEQ) IN (:%s)", bind));
		params.put(bind, idTuples);
	}

}
