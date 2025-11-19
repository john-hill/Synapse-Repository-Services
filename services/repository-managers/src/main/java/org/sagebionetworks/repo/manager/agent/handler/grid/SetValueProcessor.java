package org.sagebionetworks.repo.manager.agent.handler.grid;

import org.json.JSONObject;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.RowView;
import org.sagebionetworks.repo.model.grid.patch.ConValue;
import org.sagebionetworks.repo.model.grid.update.SetValue;


public interface SetValueProcessor<T extends SetValue> {

	ConValue createConValue(RowView row, T sv, JSONObject rawSetValue);
	
	Class<? extends T> getSetValueClass();

}
