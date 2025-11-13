package org.sagebionetworks.repo.manager.agent.handler.grid;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.json.JSONObject;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.RowView;
import org.sagebionetworks.repo.model.grid.patch.ConValue;
import org.sagebionetworks.repo.model.grid.update.SetValue;

public class SetValueProcessorFactory implements SetValueProcessor<SetValue> {
	
	private final Map<Class<? extends SetValue>, SetValueProcessor<?>> processorMap;

	SetValueProcessorFactory(List<SetValueProcessor<?>> list) {
		this.processorMap = new HashMap<>();
		for (SetValueProcessor<?> processor : list) {
			Class<?> setValueType = extractSetValueType(processor);
			if (setValueType != null) {
				processorMap.put((Class<? extends SetValue>) setValueType, processor);
			}
		}
	}

	@Override
	public Optional<ConValue> createConValue(RowView row, SetValue sv, JSONObject rawSetValue) {
		SetValueProcessor processor = processorMap.get(sv.getClass());
		if (processor == null) {
			throw new IllegalArgumentException("No processor found for SetValue type: " + sv.getClass().getName());
		}
		return processor.createConValue(row, sv, rawSetValue);
	}

	private Class<?> extractSetValueType(SetValueProcessor<?> processor) {
		Class<?> clazz = processor.getClass();
		java.lang.reflect.Type[] interfaces = clazz.getGenericInterfaces();
		for (java.lang.reflect.Type type : interfaces) {
			if (type instanceof java.lang.reflect.ParameterizedType) {
				java.lang.reflect.ParameterizedType paramType = (java.lang.reflect.ParameterizedType) type;
				if (paramType.getRawType().equals(SetValueProcessor.class)) {
					return (Class<?>) paramType.getActualTypeArguments()[0];
				}
			}
		}
		return null;
	}
}
