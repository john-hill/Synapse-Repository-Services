package org.sagebionetworks.table.cluster.avro.translator;

public class StringTranslator implements Translator {

	@Override
	public Object rowToAvro(String value) {
		return value;
	}

	@Override
	public String avroToRow(Object value) {
		return value instanceof String ? (String) value
				: value != null ? value.toString() : null;
	}

}
