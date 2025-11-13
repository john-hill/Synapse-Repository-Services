package org.sagebionetworks.repo.manager.agent.handler.grid;

import java.util.Optional;

import org.json.JSONObject;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.RowView;
import org.sagebionetworks.repo.model.grid.patch.ConValue;
import org.sagebionetworks.repo.model.grid.update.RegexExtractSetValue;

public class RegexSetValueProcessor implements SetValueProcessor<RegexExtractSetValue> {

	@Override
	public Optional<ConValue> createConValue(RowView row, RegexExtractSetValue sv, JSONObject rawSetValue) {
		// TODO Auto-generated method stub
		return null;
	}

}
