package org.sagebionetworks.repo.model.grid.patch;

public interface ConValueTranslator {

	Object stringToObject(String string);
	
	String objectToString(Object object);
}
