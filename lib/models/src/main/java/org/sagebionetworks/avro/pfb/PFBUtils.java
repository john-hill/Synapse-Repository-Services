package org.sagebionetworks.avro.pfb;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.avro.generic.GenericRecord;
import org.apache.avro.specific.SpecificRecord;
import org.sagebionetworks.util.ValidateArgument;

public class PFBUtils {

	/**
	 * Create a new SpecificRecord given a GenericRecord;
	 * 
	 * @param <T>
	 * @param record
	 * @param clazz
	 * @return
	 */
	public static <T extends SpecificRecord> T createSpecificRecord(GenericRecord record, Class<? extends T> clazz) {
		ValidateArgument.required(clazz, "Clazz");
		if (record == null) {
			return null;
		}
		try {
			T result = clazz.getDeclaredConstructor().newInstance((Object[]) null);
			record.getSchema().getFields().forEach(f -> {
				result.put(f.pos(), record.get(f.pos()));
			});
			return result;
		} catch (InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException
				| NoSuchMethodException | SecurityException e) {
			throw new IllegalArgumentException(e);
		}
	}

	/**
	 * Convert a list of GenericRecords into a list of SpecificRecords.
	 * 
	 * @param <T>
	 * @param toTranslate
	 * @param clazz
	 * @return
	 */
	public static <T extends SpecificRecord> List<T> translateGeneric(List<?> toTranslate, Class<? extends T> clazz) {
		ValidateArgument.required(clazz, "Clazz");
		if (toTranslate == null) {
			return null;
		}
		List<T> translated = new ArrayList<>(toTranslate.size());
		toTranslate.forEach(n -> {
			if (n instanceof SpecificRecord) {
				translated.add((T) n);
			} else {
				translated.add(createSpecificRecord((GenericRecord) n, clazz));
			}
		});
		return translated;
	}

	/**
	 * Convert the passed object into a string. Note: Avro will load strings as
	 * {@link CharSequence} without proper hash and equals implementations. By
	 * converting these values to actual strings, hash() and equals() works as
	 * expected.
	 * 
	 * @param in
	 * @return
	 */
	public static String createString(Object in) {
		return in == null ? null : in.toString();
	}

	/**
	 * Convert a map of objects into a map of strings. Note: Avro will load strings
	 * as {@link CharSequence} without proper hash and equals implementations. By
	 * converting these values to actual strings, hash() and equals() works as
	 * expected.
	 * 
	 * @param map
	 * @return
	 */
	public static Map<String, String> createMap(Map<?, ?> map) {
		if (map == null) {
			return null;
		}
		Map<String, String> result = new HashMap<>(map.size());
		map.forEach((k, v) -> {
			result.put(createString(k), createString(v));
		});
		return result;
	}

}
