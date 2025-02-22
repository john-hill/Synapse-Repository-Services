package org.sagebionetworks.table.cluster.avro.translator;

public class DoubleTranslator implements AvroRowTranslator {

	@Override
	public Object rowToAvro(String value) {
		return Double.parseDouble(value);
	}

	@Override
	public String avroToRow(Object value) {
		return value.toString();
	}

}
