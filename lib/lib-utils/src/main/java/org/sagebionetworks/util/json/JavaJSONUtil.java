package org.sagebionetworks.util.json;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.json.JSONArray;
import org.json.JSONObject;
import org.sagebionetworks.util.ValidateArgument;
import org.sagebionetworks.util.json.translator.ByteArrayTranslator;
import org.sagebionetworks.util.json.translator.DateTranslator;
import org.sagebionetworks.util.json.translator.EnumTranslator;
import org.sagebionetworks.util.json.translator.IdentityTranslator;
import org.sagebionetworks.util.json.translator.JSONEntityTranslator;
import org.sagebionetworks.util.json.translator.JSONType;
import org.sagebionetworks.util.json.translator.TimestampTranslator;
import org.sagebionetworks.util.json.translator.Translator;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

/**
 * A utility to write/read simple Java objects to/from JSON.
 * 
 */
public class JavaJSONUtil {

	public static final List<Translator<?, ?>> TRANSLATORS = Collections.unmodifiableList(Arrays.asList(
			new IdentityTranslator<>(Long.class, long.class), new IdentityTranslator<>(Integer.class, int.class),
			new IdentityTranslator<>(String.class), new IdentityTranslator<>(Boolean.class, boolean.class),
			new IdentityTranslator<>(Double.class, double.class), new ByteArrayTranslator(), new DateTranslator(),
			new TimestampTranslator(), new JSONEntityTranslator(), new EnumTranslator()));

	/**
	 * Write the provided list of simple Java objects to a JSONArray. Each object
	 * will be a single JSONObject within the resulting JSONArray.
	 * 
	 * @param objects
	 * @return Returns {@link Optional#empty()} if no data was written, else a new
	 *         JSONArray that contains the data of the provide list of Java objects.
	 */
	public static Optional<JSONArray> writeToJSON(List<?> objects) {
		ValidateArgument.required(objects, "objects");
		JSONArray array = new JSONArray();
		for (Object object : objects) {
			writeToJSON(object).ifPresent(o -> array.put(o));
		}
		return array.length() > 0 ? Optional.of(array) : Optional.empty();
	}

	/**
	 * Write a single simple Java object to a JSONObject.
	 * 
	 * @param object
	 * @return Returns {@link Optional#empty()} if no data was written, else a new
	 *         JSONObject that contains the data of the provided Java object.
	 * 
	 */
	public static Optional<JSONObject> writeToJSON(Object object) {
		return writeToJSON(TRANSLATORS, object);
	}

	/**
	 * Read a list of simple Java objects from the provided JSONArray.
	 * 
	 * @param <T>
	 * @param clazz The class of the resulting Java objects.
	 * @param array The JSONArray containing the data to read.
	 * @return
	 */
	public static <T> List<T> readFromJSON(Class<? extends T> clazz, JSONArray array) {
		ValidateArgument.required(array, "array");
		ValidateArgument.required(clazz, "clazz");

		List<T> list = new ArrayList<>(array.length());
		array.forEach(o -> {
			if (!(o instanceof JSONObject)) {
				throw new IllegalArgumentException("Expected JSONObjects but found: " + o.getClass().getName());
			}
			list.add(readFromJSON(clazz, (JSONObject) o));
		});
		return list;
	}

	/**
	 * Read a list of objects directly from the provided Stream
	 * @param <T>
	 * @param clazz
	 * @param reader
	 * @return
	 */
	public static <T> List<T> readFromJSON(Class<? extends T> clazz, Reader reader) {
		ValidateArgument.required(reader, "reader");
		ValidateArgument.required(clazz, "clazz");
		Map<String, Object> typeMap = Arrays.stream(clazz.getDeclaredFields())
				.collect(Collectors.toMap(f -> f.getName(), f -> f.getType()));
		try {
			List<T> list = new ArrayList<>();
			try (JsonReader stream = new JsonReader(reader)) {
				stream.beginArray();
				while (stream.hasNext()) {
					list.add(readFromJSON(clazz, readObject(typeMap, stream)));
				}
				stream.endArray();
			}
			return list;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Read a single JSONObject from the provided stream.
	 * 
	 * @param stream
	 * @return
	 * @throws IOException
	 */
	public static JSONObject readObject(Map<String, Object> typeMap, JsonReader stream) throws IOException {
		JSONObject object = new JSONObject();
		String name = null;
		stream.beginObject();
		while (stream.hasNext()) {
			JsonToken token = stream.peek();
			switch (token) {
			case NAME:
				name = stream.nextName();
				break;
			case NUMBER:
				Object type = typeMap.get(name);
				if (Long.class.equals(type)) {
					object.put(name, stream.nextLong());
				} else if (Integer.class.equals(type)) {
					object.put(name, stream.nextInt());
				} else if (Double.class.equals(type)) {
					object.put(name, stream.nextDouble());
				} else if (Date.class.equals(type)) {
					object.put(name, stream.nextLong());
				} else if (Timestamp.class.equals(type)) {
					object.put(name, stream.nextLong());
				} else if (int.class.equals(type)) {
					object.put(name, stream.nextInt());
				} else if (long.class.equals(type)) {
					object.put(name, stream.nextLong());
				} else {
					throw new IllegalArgumentException("Unknown number type: " + type);
				}
				break;
			case BOOLEAN:
				object.put(name, stream.nextBoolean());
				break;
			case STRING:
				object.put(name, stream.nextString());
				break;
			default:
				throw new IllegalArgumentException("Unknown type: " + token);
			}
		}
		stream.endObject();
		return object;
	}

	/**
	 * Read a single simple Java object from the provide JSONObject.
	 * 
	 * @param <T>
	 * @param clazz  The class of the resulting Java object.
	 * @param object
	 * @return
	 */
	public static <T> T readFromJSON(Class<? extends T> clazz, JSONObject object) {
		return readFromJSON(TRANSLATORS, clazz, object);
	}

	@SuppressWarnings("unchecked")
	static <F, J> Optional<JSONObject> writeToJSON(List<Translator<?, ?>> translators, Object object) {
		ValidateArgument.required(translators, "translators");
		ValidateArgument.required(object, "object");

		JSONObject json = new JSONObject();
		boolean wasWritten = false;
		Class<? extends Object> clazz = object.getClass();
		for (Field field : clazz.getDeclaredFields()) {
			if (!Modifier.isStatic(field.getModifiers())) {
				try {
					field.setAccessible(true);
					Object value = field.get(object);
					if (value != null) {
						Translator<F, J> transaltor = findTranslator(translators, field.getType());
						json.put(field.getName(), transaltor.translateFromJavaToJSON((F) value));
						wasWritten = true;
					}
				} catch (IllegalArgumentException | IllegalAccessException e) {
					throw new RuntimeException(e);
				}
			}
		}
		return wasWritten ? Optional.of(json) : Optional.empty();
	}

	/**
	 * Helper to find a translator for the provided type.
	 * 
	 * @param <F>
	 * @param <J>
	 * @param translators The is of possible translator.
	 * @param type
	 * @return
	 */
	@SuppressWarnings("unchecked")
	static <F, J> Translator<F, J> findTranslator(List<Translator<?, ?>> translators, Class<?> type) {
		ValidateArgument.required(translators, "translators");
		ValidateArgument.required(type, "type");

		Translator<F, J> transaltor = (Translator<F, J>) translators.stream().filter(t -> t.canTranslate(type))
				.findFirst()
				.orElseThrow(() -> new IllegalArgumentException("No translator found for: " + type.getName()));
		return transaltor;
	}

	@SuppressWarnings("unchecked")
	static <T, F, J> T readFromJSON(List<Translator<?, ?>> translators, Class<? extends T> clazz,
			JSONObject jsonObject) {
		ValidateArgument.required(translators, "translators");
		ValidateArgument.required(clazz, "type");
		ValidateArgument.required(jsonObject, "jsonObject");

		try {
			T newObject = (T) createNewInstance(clazz);
			for (Field field : clazz.getDeclaredFields()) {
				if (!Modifier.isStatic(field.getModifiers())) {
					if (jsonObject.has(field.getName())) {
						field.setAccessible(true);
						Translator<F, J> transaltor = findTranslator(translators, field.getType());
						J jsonValue = (J) JSONType.lookupType(transaltor.getJSONClass()).getFromJSON(field.getName(),
								jsonObject);
						field.set(newObject, transaltor.translateFromJSONToJava((Class<F>) field.getType(), jsonValue));
					}
				}
			}
			return newObject;
		} catch (IllegalAccessException | IllegalArgumentException e) {
			throw new IllegalArgumentException(e);
		}
	}

	/**
	 * Create a new Java object from the given class. The calls must provide a zero
	 * argument constructor.
	 * 
	 * @param type
	 * @return
	 */
	public static Object createNewInstance(Class<?> type) {
		try {
			return type.getConstructor((Class<?>[]) null).newInstance((Object[]) null);
		} catch (InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException
				| SecurityException e) {
			throw new RuntimeException(e);
		} catch (NoSuchMethodException e) {
			throw new IllegalArgumentException("A zero argument constructor could not be found for: " + type.getName());
		}
	}

}
