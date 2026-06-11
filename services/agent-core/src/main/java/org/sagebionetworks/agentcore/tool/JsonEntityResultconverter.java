package org.sagebionetworks.agentcore.tool;

import java.lang.reflect.Type;

import org.sagebionetworks.repo.model.jdo.JDOSecondaryPropertyUtils;
import org.sagebionetworks.schema.adapter.JSONEntity;
import org.springframework.ai.tool.execution.ToolCallResultConverter;

/**
 * Used to convert return types of {@link JSONEntity} to JSON strings.
 */
public class JsonEntityResultconverter implements ToolCallResultConverter {

	@Override
	public String convert(Object result, Type returnType) {
		if (!(result instanceof JSONEntity)) {
			throw new IllegalArgumentException("This converted can only be used for JSONEntity results");
		}
		JSONEntity jsonEntity = (JSONEntity) result;
		return JDOSecondaryPropertyUtils.createJSONFromObject(jsonEntity);
	}

}
