package org.sagebionetworks.repo.manager.agent.handler.grid;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.RowView;
import org.sagebionetworks.repo.model.grid.patch.ConValue;
import org.sagebionetworks.repo.model.grid.update.TemplateSetValue;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.stereotype.Service;

@Service
public class TemplateSetValueProcessor implements SetValueProcessor<TemplateSetValue> {

	@Override
	public Optional<ConValue> createConValue(RowView row, TemplateSetValue sv, JSONObject rawSetValue) {
		ValidateArgument.required(row, "row");
		ValidateArgument.required(sv, "TemplateSetValue");
		ValidateArgument.required(sv.getSourceTemplate(), "TemplateSetValue.sourceTempalte");
		ValidateArgument.required(rawSetValue, "rawSetValue");

		JSONObject rowValue = row.getRowObject().getData().getRowJsonDocument();

		Pattern pattern = Pattern.compile("\\{([^}]+)\\}");
		Matcher matcher = pattern.matcher(sv.getSourceTemplate());
		StringBuffer result = new StringBuffer();

		while (matcher.find()) {
			String columnName = matcher.group(1);
			Object value = rowValue.opt(columnName);
			if (value != null) {
				matcher.appendReplacement(result, Matcher.quoteReplacement(value.toString()));
			} else {
				matcher.appendReplacement(result, Matcher.quoteReplacement("{" + columnName + "}"));
			}
		}
		matcher.appendTail(result);
		String replacedTemplate = result.toString();
		if (sv.getPattern() != null) {
			matcher = Pattern.compile(sv.getPattern()).matcher(replacedTemplate);
			String replacement = sv.getReplacement() != null ? sv.getReplacement() : "$1";
			StringBuffer patternResult = new StringBuffer();
			if (matcher.find()) {
				matcher.appendReplacement(patternResult, replacement);
				matcher.appendTail(patternResult);
				replacedTemplate = patternResult.toString();
			}
		}
		return Optional.of(ConValue.fromCompact(new JSONArray("[" + replacedTemplate + "]")));
	}

	@Override
	public Class<? extends TemplateSetValue> getSetValueClass() {
		return TemplateSetValue.class;
	}

}
