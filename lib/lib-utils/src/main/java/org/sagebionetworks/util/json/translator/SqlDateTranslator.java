package org.sagebionetworks.util.json.translator;

public class SqlDateTranslator implements Translator<java.sql.Date, Long> {

	@Override
	public boolean canTranslate(Class<?> fieldType) {
		return java.sql.Date.class.equals(fieldType);
	}

	@Override
	public java.sql.Date translateFromJSONToJava(Class<? extends java.sql.Date> type, Long jsonValue) {
		return new java.sql.Date(jsonValue);
	}

	@Override
	public Long translateFromJavaToJSON(java.sql.Date fieldValue) {
		return fieldValue.getTime();
	}

	@Override
	public Class<? extends Long> getJSONClass() {
		return Long.class;
	}

}
