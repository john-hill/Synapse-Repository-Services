package org.sagebionetworks.table.cluster.avro.translator;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;

public class ListTranslator implements Translator {

	@Override
	public Object rowToAvro(String value) {
		JSONArray array = new JSONArray(value);
		List<Object> list = new ArrayList<>(array.length());
		array.forEach(v -> {
			list.add(v);
		});
		return list;
	}

	@Override
	public String avroToRow(Object value) {
		List<Object> list = (List<Object>) value;
		JSONArray array = new JSONArray();
		list.forEach(v -> {
			array.put(v);
		});
		return array.toString();
	}

}
