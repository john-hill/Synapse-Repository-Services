package org.sagebionetworks.repo.manager.agent.handler.grid;

import org.json.JSONObject;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.RowView;
import org.sagebionetworks.repo.model.grid.patch.ConType;
import org.sagebionetworks.repo.model.grid.patch.ConValue;
import org.sagebionetworks.repo.model.grid.update.LiteralSetValue;
import org.springframework.stereotype.Service;

@Service
public class LiteralSetValueProcessor implements SetValueProcessor<LiteralSetValue> {

	@Override
	public ConValue createConValue(RowView row, LiteralSetValue sv, JSONObject rawSetValue) {
		if (!rawSetValue.has("value")) {
			return new ConValue(ConType.UNDEFINED, null);
		}
		if (rawSetValue.isNull("value")) {
			return new ConValue(ConType.NULL, null);
		}
		return new ConValue(ConType.fromValue(sv.getValue()), sv.getValue());
	}

	@Override
	public Class<? extends LiteralSetValue> getSetValueClass() {
		return LiteralSetValue.class;
	}

}
