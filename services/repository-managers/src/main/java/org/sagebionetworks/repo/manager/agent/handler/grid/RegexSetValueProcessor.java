package org.sagebionetworks.repo.manager.agent.handler.grid;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.RowView;
import org.sagebionetworks.repo.model.grid.patch.ConType;
import org.sagebionetworks.repo.model.grid.patch.ConValue;
import org.sagebionetworks.repo.model.grid.update.OnMatchFailure;
import org.sagebionetworks.repo.model.grid.update.RegexExtractSetValue;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.stereotype.Service;

@Service
public class RegexSetValueProcessor implements SetValueProcessor<RegexExtractSetValue> {

	@Override
	public ConValue createConValue(RowView row, RegexExtractSetValue sv, JSONObject rawSetValue) {
		ValidateArgument.required(row, "row");
		ValidateArgument.required(sv, "RegexExtractSetValue");
		ValidateArgument.required(sv.getColumnName(), "RegexExtractSetValue.columnName");
		ValidateArgument.required(sv.getGroupIndex(), "RegexExtractSetValue.groupIndex");
		ValidateArgument.required(sv.getPattern(), "RegexExtractSetValue.pattern");
		ValidateArgument.required(sv.getSourceColumnName(), "RegexExtractSetValue.sourceColumnName");
		ValidateArgument.required(rawSetValue, "rawSetValue");
		
		OnMatchFailure onMatchFailure = sv.getOnMatchFailure() != null ? sv.getOnMatchFailure() : OnMatchFailure.SET_NULL;
		JSONObject rowValue = row.getRowObject().getData().getRowJsonDocument();
		Object sourceValue = rowValue.opt(sv.getSourceColumnName());
		
		if (sourceValue == null) {
			return handleNoMatch(onMatchFailure, rowValue, sv.getColumnName());
		}

		Matcher matcher = Pattern.compile(sv.getPattern()).matcher(sourceValue.toString());
		String newValue = matcher.find() ? matcher.group(sv.getGroupIndex().intValue()) : null;
		
		if (newValue == null) {
			return handleNoMatch(onMatchFailure, rowValue, sv.getColumnName());
		}
		return ConValue.fromCompact(new JSONArray("[" + newValue + "]"));
	}

	ConValue handleNoMatch(OnMatchFailure onMatchFailure, JSONObject rowValue, String columnName) {
		if (onMatchFailure == OnMatchFailure.SET_NULL) {
			return new ConValue(ConType.NULL, JSONObject.NULL);
		}
		
		if (!rowValue.has(columnName)) {
			return new ConValue(ConType.UNDEFINED, null);
		}
		
		if (rowValue.isNull(columnName)) {
			return new ConValue(ConType.NULL, JSONObject.NULL);
		}
		
		return ConValue.fromCompact(new JSONArray().put(rowValue.get(columnName)));
	}

	@Override
	public Class<? extends RegexExtractSetValue> getSetValueClass() {
		return RegexExtractSetValue.class;
	}
}
