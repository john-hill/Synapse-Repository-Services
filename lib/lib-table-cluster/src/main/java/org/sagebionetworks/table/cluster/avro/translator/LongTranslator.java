package org.sagebionetworks.table.cluster.avro.translator;

public class LongTranslator implements AvroRowTranslator {

	@Override
	public Object rowToAvro(String value) {
		return Long.parseLong(value);
	}

	@Override
	public String avroToRow(Object value) {
		return value.toString();
	}

}
