package org.sagebionetworks.table.cluster.avro;

import java.util.List;

import org.apache.avro.Schema;
import org.apache.avro.Schema.Type;
import org.apache.avro.SchemaBuilder;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.table.cluster.avro.translator.BooleanTranslator;
import org.sagebionetworks.table.cluster.avro.translator.DoubleTranslator;
import org.sagebionetworks.table.cluster.avro.translator.ListTranslator;
import org.sagebionetworks.table.cluster.avro.translator.LongTranslator;
import org.sagebionetworks.table.cluster.avro.translator.StringTranslator;
import org.sagebionetworks.table.cluster.avro.translator.AvroRowTranslator;

public enum ColumnTypeAvro implements AvroRowTranslator {

	STRING(ColumnType.STRING, Schema.create(Type.STRING), new StringTranslator()),
	DOUBLE(ColumnType.DOUBLE, Schema.create(Type.DOUBLE), new DoubleTranslator()),
	INTEGER(ColumnType.INTEGER, Schema.create(Type.LONG), new LongTranslator()),
	BOOLEAN(ColumnType.BOOLEAN, Schema.create(Type.BOOLEAN), new BooleanTranslator()),
	DATE(ColumnType.DATE, Schema.create(Type.LONG), new LongTranslator()),
	FILEHANDLEID(ColumnType.FILEHANDLEID, Schema.create(Type.LONG), new LongTranslator()),
	ENTITYID(ColumnType.ENTITYID, Schema.create(Type.STRING), new StringTranslator()),
	SUBMISSIONID(ColumnType.SUBMISSIONID, Schema.create(Type.LONG), new LongTranslator()),
	EVALUATIONID(ColumnType.EVALUATIONID, Schema.create(Type.LONG), new LongTranslator()),
	LINK(ColumnType.LINK, Schema.create(Type.STRING), new StringTranslator()),
	MEDIUMTEXT(ColumnType.MEDIUMTEXT, Schema.create(Type.STRING), new StringTranslator()),
	LARGETEXT(ColumnType.LARGETEXT, Schema.create(Type.STRING), new StringTranslator()),
	USERID(ColumnType.USERID, Schema.create(Type.LONG), new LongTranslator()),
	STRING_LIST(ColumnType.STRING_LIST, Schema.createArray(Schema.create(Type.STRING)), new ListTranslator()),
	INTEGER_LIST(ColumnType.INTEGER_LIST, Schema.createArray(Schema.create(Type.LONG)), new ListTranslator()),
	BOOLEAN_LIST(ColumnType.BOOLEAN_LIST, Schema.createArray(Schema.create(Type.BOOLEAN)), new ListTranslator()),
	DATE_LIST(ColumnType.DATE_LIST, Schema.createArray(Schema.create(Type.LONG)), new ListTranslator()),
	ENTITYID_LIST(ColumnType.ENTITYID_LIST, Schema.createArray(Schema.create(Type.STRING)), new ListTranslator()),
	USERID_LIST(ColumnType.USERID_LIST, Schema.createArray(Schema.create(Type.LONG)), new ListTranslator()),
	JSON(ColumnType.JSON, Schema.create(Type.STRING), new StringTranslator());

	private final ColumnType type;
	private final Schema schema;
	private final AvroRowTranslator translator;

	private ColumnTypeAvro(ColumnType type, Schema schema, AvroRowTranslator translator) {
		this.type = type;
		this.schema = schema;
		this.translator = translator;
	}

	public ColumnType getType() {
		return type;
	}

	public Schema getSchema() {
		return schema;
	}

	@Override
	public Object rowToAvro(String value) {
		if (value == null) {
			return null;
		}
		return translator.rowToAvro(value);
	}

	@Override
	public String avroToRow(Object value) {
		if (value == null) {
			return null;
		}
		return translator.avroToRow(value);
	}

	public static ColumnTypeAvro matchType(ColumnType type) {
		for(ColumnTypeAvro info: ColumnTypeAvro.values()){
			if(info.type.equals(type)){
				return info;
			}
		}
		throw new IllegalArgumentException("Unknown ColumnType: "+type);
	}
	
	public static Schema toAvro(String name, List<ColumnModel> models) {
		var builder = SchemaBuilder.record(name).fields();
		models.stream().forEach(c -> {
			var field = builder.name(c.getName())
					.type(Schema.createUnion(List.of(Schema.create(Type.NULL), ColumnTypeAvro.matchType(c.getColumnType()).getSchema() )));
			if (c.getDefaultValue() == null) {
				field.noDefault();
			} else {
				field.withDefault(ColumnTypeAvro.matchType(c.getColumnType()).rowToAvro(c.getDefaultValue()));
			}
		});
		return builder.endRecord();
	}
	
	/**
	 * Given a {@link Schema} type find the best matching {@link ColumnType}.
	 * 
	 * @param typeSchema
	 * @return
	 */
	public static ColumnType getColumnType(Schema typeSchema) {
		switch (typeSchema.getType()) {
		case ARRAY:
			return getListType(typeSchema.getElementType());
		case BOOLEAN:
			return ColumnType.BOOLEAN;
		case DOUBLE:
		case FLOAT:
			return ColumnType.DOUBLE;
		case INT:
		case LONG:
			return ColumnType.INTEGER;
		case STRING:
			return ColumnType.STRING;
		default:
			throw new IllegalArgumentException("Unknown type: " + typeSchema.getType());
		}
	}

	static ColumnType getListType(Schema elementType) {
		switch (elementType.getType()) {
		case BOOLEAN:
			return ColumnType.BOOLEAN_LIST;
		case INT:
		case LONG:
			return ColumnType.INTEGER_LIST;
		case STRING:
			return ColumnType.STRING_LIST;
		default:
			throw new IllegalArgumentException("Unknown list type: " + elementType.getType());
		}
	}
}
