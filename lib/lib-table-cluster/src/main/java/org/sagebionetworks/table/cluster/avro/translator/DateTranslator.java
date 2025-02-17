package org.sagebionetworks.table.cluster.avro.translator;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

import org.joda.time.format.ISODateTimeFormat;
import org.sagebionetworks.util.TimeUtils;

public class DateTranslator implements Translator {
	
	private static final SimpleDateFormat gmtDateFormatter;
	static {
		gmtDateFormatter = new SimpleDateFormat("yy-M-d H:m:s.SSS");
		gmtDateFormatter.setTimeZone(TimeZone.getTimeZone("UTC"));
	}

	@Override
	public Object rowToAvro(String value) {
		/*
		 * Dates can be a long or string. Long is tried first, then the
		 * string.
		 */
		try {
			return Long.parseLong(value);
		} catch (NumberFormatException e) {
			try {
				return TimeUtils.parseSqlDate(value);
			} catch (IllegalArgumentException e2){
				// To keep the error type and messages consistent,
				// we use Joda instead of Java 8's Instant to parse
				// since TimeUtils.parseSqlDate already uses Joda
				return ISODateTimeFormat.dateTimeParser().parseDateTime(value).getMillis();
			}
		}
	}

	@Override
	public String avroToRow(Object value) {
		return gmtDateFormatter.format(new Date((long)value));
	}

}
