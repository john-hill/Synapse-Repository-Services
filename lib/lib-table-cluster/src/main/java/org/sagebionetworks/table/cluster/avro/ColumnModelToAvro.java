package org.sagebionetworks.table.cluster.avro;

import java.util.ArrayList;
import java.util.List;

import org.apache.avro.Schema;
import org.apache.avro.Schema.Type;
import org.apache.avro.SchemaBuilder;
import org.json.JSONArray;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.table.cluster.SQLUtils;
import org.sagebionetworks.table.query.util.ColumnTypeListMappings;

public class ColumnModelToAvro {

	public static Schema toAvro(String name, List<ColumnModel> models) {
		var builder = SchemaBuilder.record(name).fields();
		models.stream().forEach(c -> {
			var field = builder.name(c.getName()).type(typeToSchema(c.getColumnType(), c.getEnumValues()));
			if (c.getDefaultValue() == null) {
				field.noDefault();
			} else {
				field.withDefault(createValue(c.getColumnType(), c.getDefaultValue()));
			}
		});
		return builder.endRecord();
	}

	static Schema typeToSchema(ColumnType type, List<String> enumValues) {
		switch (type) {
		case BOOLEAN:
			Schema.createUnion(List.of(Schema.create(Type.NULL)));
			return Schema.create(Type.BOOLEAN);
		case BOOLEAN_LIST:
			return Schema.createArray(Schema.create(Type.BOOLEAN));
		case DATE:
			return Schema.create(Type.LONG);
		case DATE_LIST:
			return Schema.createArray(Schema.create(Type.LONG));
		case DOUBLE:
			return Schema.create(Type.FLOAT);
		case ENTITYID:
			return Schema.create(Type.LONG);
		case ENTITYID_LIST:
			return Schema.createArray(Schema.create(Type.LONG));
		case EVALUATIONID:
			return Schema.create(Type.LONG);
		case FILEHANDLEID:
			return Schema.create(Type.LONG);
		case INTEGER:
			return Schema.create(Type.LONG);
		case INTEGER_LIST:
			return Schema.createArray(Schema.create(Type.LONG));
		case JSON:
			return Schema.create(Type.STRING);
		case LARGETEXT:
			return Schema.create(Type.STRING);
		case LINK:
			return Schema.create(Type.STRING);
		case MEDIUMTEXT:
			return Schema.create(Type.STRING);
		case STRING:
			if (enumValues != null && !enumValues.isEmpty()) {
				return Schema.createEnum(null, null, null, enumValues);
			} else {
				return Schema.create(Type.STRING);
			}
		case STRING_LIST:
			return Schema.createArray(Schema.create(Type.STRING));
		case SUBMISSIONID:
			return Schema.create(Type.LONG);
		case USERID:
			return Schema.create(Type.LONG);
		case USERID_LIST:
			return Schema.createArray(Schema.create(Type.LONG));
		default:
			throw new IllegalArgumentException("Unknown type: " + type);
		}
	}

	/**
	 * Create a Java object representation for the given value based on its column
	 * type.
	 * 
	 * @param type
	 * @param value
	 * @return
	 */
	static Object createValue(ColumnType type, String value) {
		Object objectValue = SQLUtils.parseValueForDB(type, value);
		if (objectValue == null) {
			return null;
		}
		if (ColumnTypeListMappings.isList(type)) {
			// List values are JSON arrays.
			JSONArray array = new JSONArray(value);
			List<Object> list = new ArrayList<>(array.length());
			array.forEach(v -> {
				list.add(v);
			});
			return list;
		} else {
			// not a list
			return objectValue;
		}
	}
}
