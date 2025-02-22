package org.sagebionetworks.table.cluster.avro.translator;

import org.sagebionetworks.repo.model.table.Row;

public interface AvroRowTranslator {

	/**
	 * Translate from a {@link Row} value String value to an Avro object.
	 * 
	 * @param value
	 * @return
	 */
	Object rowToAvro(String value);

	/**
	 * Translate from an Avro object to a {@link Row} value String.
	 * @param value
	 * @return
	 */
	String avroToRow(Object value);
}
