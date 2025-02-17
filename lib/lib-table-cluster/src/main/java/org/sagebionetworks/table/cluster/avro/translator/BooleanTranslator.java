package org.sagebionetworks.table.cluster.avro.translator;

public class BooleanTranslator implements Translator {

	@Override
	public Object rowToAvro(String value) {
		return Boolean.parseBoolean(value);
	}

	@Override
	public String avroToRow(Object value) {
		return value.toString();
	}

}
