package org.sagebionetworks.repo.model.grid.patch;

/**
 * The value of a con node is JSON-like value. The value can be any JSON value,
 * including null, true, false, numbers, strings, arrays, objects, binary blobs,
 * undefined value, and logical clock timestamp
 */
public enum ConType {

	_null(null),
	_boolean(null),
	_long(null),
	_double(null),
	string(null),
	json_array(null),
	json_object(null),
	undefined(null),
	timestamp(null);

	ConType(ConValueTranslator translator) {
		this.translator = translator;
	}

	private ConValueTranslator translator;
}
