package org.sagebionetworks.table.cluster.utils;

import java.io.Reader;
import java.io.Writer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.sagebionetworks.repo.model.table.ColumnConstants;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.repo.model.table.CsvTableDescriptor;
import org.sagebionetworks.repo.model.table.UploadToTablePreviewRequest;

import au.com.bytecode.opencsv.CSVReader;
import au.com.bytecode.opencsv.CSVWriter;
import au.com.bytecode.opencsv.Constants;

public class CSVUtils {
	
	public static final String ERROR_CELLS_EXCEED_MAX = "One or more cell value exceeds the maximum number of characters: "+ ColumnConstants.MAX_LARGE_TEXT_CHARACTERS;
	/**
	 * When searching for a type this setups the order we check for.  Not all types are included.
	 */
	private static final ColumnType[] typesToCheck = new ColumnType[]{ColumnType.BOOLEAN, ColumnType.INTEGER, ColumnType.DOUBLE, ColumnType.DATE, ColumnType.ENTITYID, ColumnType.STRING, ColumnType.MEDIUMTEXT, ColumnType.LARGETEXT};

	/**
	 * Create CSVReader with the correct parameters using the provided parameters or default values.
	 * @param reader
	 * @param body
	 * @param contentType
	 * @return
	 */
	public static CSVReader createCSVReader(Reader reader, CsvTableDescriptor descriptor, Long linesToSkip) {
		char separator = Constants.DEFAULT_SEPARATOR;
		char quotechar = Constants.DEFAULT_QUOTE_CHARACTER;
		char escape = Constants.DEFAULT_ESCAPE_CHARACTER;
		int skipLines = 0;
		if(descriptor != null){
			if (descriptor.getSeparator() != null) {
				if (descriptor.getSeparator().length() != 1) {
					throw new IllegalArgumentException(
							"CsvTableDescriptor.separator must be exactly one character.");
				}
				separator = descriptor.getSeparator().charAt(0);
			}
			if (descriptor.getQuoteCharacter() != null) {
				if (descriptor.getQuoteCharacter().length() != 1) {
					throw new IllegalArgumentException(
							"CsvTableDescriptor.quoteCharacter must be exactly one character.");
				}
				quotechar = descriptor.getQuoteCharacter()
						.charAt(0);
			}
			if (descriptor.getEscapeCharacter() != null) {
				if (descriptor.getEscapeCharacter().length() != 1) {
					throw new IllegalArgumentException(
							"CsvTableDescriptor.escapeCharacter must be exactly one character.");
				}
				escape = descriptor.getEscapeCharacter()
						.charAt(0);
			}			
		}
		if (linesToSkip != null) {
			skipLines = linesToSkip.intValue();
		}
		// Create the reader.
		return new CSVReader(reader, separator, quotechar, escape, skipLines);
	}
	
	/**
	 * Is the first line a header.  If null then true.
	 * @param descriptor
	 * @return
	 */
	public static boolean isFirstRowHeader(CsvTableDescriptor descriptor){
		if(descriptor != null){
			if(descriptor.getIsFirstLineHeader() != null){
				return descriptor.getIsFirstLineHeader();
			}
		}
		// default to true
		return true;
	}
	
	/**
	 * Do a full scan?  If null then false.
	 * 
	 * @param request
	 * @return
	 */
	public static boolean doFullScan(UploadToTablePreviewRequest request){
		if(request != null){
			if(request.getDoFullFileScan() != null){
				return request.getDoFullFileScan();
			}
		}
		return false;
	}
	
	/**
	 * Check the types for each column.
	 * @param cells
	 * @param currentTypes
	 */
	public static void checkTypes(String[] cells, ColumnModel[] currentTypes){
		// Check the type of each column
		for(int i=0; i<cells.length; i++){
			currentTypes[i] = checkType(cells[i], currentTypes[i]);
		}
	}
	

	/**
	 * Check if the given value is compatible with the given columnType.
	 * If not, a ColumnModel that is compatible will be found and returned.
	 *
	 * @param value If null, then the currentType will be returned.
	 * @param currentType If null, then a compatible type will be returned.
	 * @return
	 */
	public static ColumnModel checkType(String value, ColumnModel currentType) {
		// We can tell nothing from null or empty cells.
		if(value == null || "".equals(value.trim())){
			return currentType;
		}
		// Empty JSON arrays provide no element type information, treat as no data.
		if (isEmptyJsonArray(value)) {
			return currentType;
		}
		long currentMaxSize = 0;
		if(currentType != null){
			currentMaxSize = currentType.getMaximumSize();
		}
		boolean currentIsJsonType = currentType != null && isJsonColumnType(currentType.getColumnType());
		ColumnType detectedJsonType = detectJsonColumnType(value);
		// If both are JSON types (or first value is JSON), resolve within the JSON type hierarchy
		if (detectedJsonType != null && (currentType == null || currentIsJsonType)) {
			ColumnType resolvedType = currentIsJsonType
					? widenJsonType(currentType.getColumnType(), detectedJsonType)
					: detectedJsonType;
			ColumnModel cm = new ColumnModel();
			cm.setColumnType(resolvedType);
			cm.setMaximumSize(Math.max(value.length(), currentMaxSize));
			return cm;
		}
		// If current is a JSON type but new value is not JSON (or vice versa), fall to STRING+ detection.
		// JSON types are not in typesToCheck, so start from STRING when transitioning away from JSON.
		int startIndex;
		if (currentIsJsonType) {
			startIndex = findIndexOfType(ColumnType.STRING);
		} else {
			startIndex = findIndexOf(currentType);
		}
		// Try each type in order
		for(int i=startIndex; i<typesToCheck.length; i++){
			ColumnModel cm = new ColumnModel();
			cm.setColumnType(typesToCheck[i]);
			long maxSize = Math.max(value.length(), currentMaxSize);
			cm.setMaximumSize(maxSize);
			try {
				TableModelUtils.validateValue(value, cm);
				// We have a match.
				return cm;
			} catch (IllegalArgumentException e) {
				// This type will not work so try the next.
				continue;
			}
		}
		// We failed to match a type
		throw new IllegalArgumentException(ERROR_CELLS_EXCEED_MAX);
	}

	/**
	 * Find the index of the given ColumnModel from the typesToCheck.
	 * @param currentType
	 * @return
	 */
	static int findIndexOf(ColumnModel currentType){
		if(currentType == null){
			return 0;
		}
		return findIndexOfType(currentType.getColumnType());
	}

	/**
	 * Find the index of the given ColumnType from the typesToCheck.
	 * @param type
	 * @return
	 */
	static int findIndexOfType(ColumnType type) {
		for (int i = 0; i < typesToCheck.length; i++) {
			if (typesToCheck[i].equals(type)) {
				return i;
			}
		}
		throw new IllegalArgumentException("Unkown ColumnType: " + type);
	}

	/**
	 * @return true if the value is a JSON array with no elements.
	 */
	static boolean isEmptyJsonArray(String value) {
		String trimmed = value.trim();
		if (!trimmed.startsWith("[")) {
			return false;
		}
		try {
			return new JSONArray(trimmed).length() == 0;
		} catch (JSONException e) {
			return false;
		}
	}

	/**
	 * @return true if the given type is a JSON or LIST column type that is detected from JSON values.
	 */
	static boolean isJsonColumnType(ColumnType type) {
		return type == ColumnType.JSON
				|| type == ColumnType.INTEGER_LIST
				|| type == ColumnType.STRING_LIST;
	}

	/**
	 * Attempt to detect a JSON column type from the given value.
	 *
	 * @return The detected JSON column type, or null if the value is not JSON or is an empty array.
	 */
	static ColumnType detectJsonColumnType(String value) {
		String trimmed = value.trim();
		if (trimmed.startsWith("{")) {
			try {
				new JSONObject(trimmed);
				return ColumnType.JSON;
			} catch (JSONException e) {
				return null;
			}
		}
		if (trimmed.startsWith("[")) {
			try {
				JSONArray array = new JSONArray(trimmed);
				// Empty arrays are handled by isEmptyJsonArray() before this method is called
				for (int i = 0; i < array.length(); i++) {
					if (array.isNull(i)) {
						return ColumnType.JSON;
					}
					Object elem = array.get(i);
					if (elem instanceof JSONObject || elem instanceof JSONArray) {
						return ColumnType.JSON;
					}
				}
				// Check if all elements are valid integers
				boolean allIntegers = true;
				for (int i = 0; i < array.length(); i++) {
					try {
						Long.parseLong(array.getString(i));
					} catch (NumberFormatException e) {
						allIntegers = false;
						break;
					}
				}
				return allIntegers ? ColumnType.INTEGER_LIST : ColumnType.STRING_LIST;
			} catch (JSONException e) {
				return null;
			}
		}
		return null;
	}

	/**
	 * Given two JSON column types, return the wider of the two.
	 * Hierarchy (narrow to wide): INTEGER_LIST < STRING_LIST < JSON
	 */
	static ColumnType widenJsonType(ColumnType current, ColumnType detected) {
		if (current == ColumnType.JSON || detected == ColumnType.JSON) {
			return ColumnType.JSON;
		}
		if (current == ColumnType.STRING_LIST || detected == ColumnType.STRING_LIST) {
			return ColumnType.STRING_LIST;
		}
		return ColumnType.INTEGER_LIST;
	}

	/**
	 * make a rough guess as to what the extension for the file should be based on the separator.
	 * 
	 * @param separator
	 * @return
	 */
	public static String guessExtension(String separator) {
		String extension = "csv"; // by default, just use csv
		if ("\t".equals(separator)) {
			extension = "tsv";
		}
		return extension;
	}

	/**
	 * make a rough guess as to what the extension for the file should be based on the separator.
	 * 
	 * @param separator
	 * @return
	 */
	public static String guessContentType(String separator) {
		String contentType = "text/csv";
		if ("\t".equals(separator)) {
			contentType = "text/tsv";
		}
		return contentType;
	}
}
