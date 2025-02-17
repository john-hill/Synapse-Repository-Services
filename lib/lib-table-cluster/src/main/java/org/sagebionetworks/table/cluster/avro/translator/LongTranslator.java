package org.sagebionetworks.table.cluster.avro.translator;

public class LongTranslator implements Translator {

	@Override
	public Object rowToAvro(String value) {
		return Long.parseLong(value);
	}

	@Override
	public String avroToRow(Object value) {
		return value.toString();
	}

}
