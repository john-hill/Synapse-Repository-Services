package org.sagebionetworks.repo.model.annotation.v2.annotaitonvalidator;

import java.util.List;

class StringValueListValidator implements AnnotationsV2ValueListValidator {

	static final int MAX_STRING_SIZE = 2000;

	@Override
	public void validate(String key, List<String> values) {
		for(String value: values){
			if(value == null){
				throw new IllegalArgumentException(NULL_IS_NOT_ALLOWED);
			}
			if (value.length() > MAX_STRING_SIZE){
				throw new IllegalArgumentException("String value too long. Each value can be at most " + MAX_STRING_SIZE + " characters.");
			}
		}
	}
}
