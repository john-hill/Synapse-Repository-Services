package org.sagebionetworks.table.cluster.avro;

import java.util.List;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.GenericRecordBuilder;
import org.sagebionetworks.avro.pfb.model.Entity;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.Row;
import org.sagebionetworks.util.ValidateArgument;

public class RowPFBUtils {
	
	private static String DELMITER= "_";
	private static String ID_VERSION_TEMPLATE = "%d"+DELMITER+"%d";

	/**
	 * Create an {@link Entity} id from the row's id and version.
	 * 
	 * @param row
	 * @return
	 */
	public static String createEntiyId(Row row) {
		ValidateArgument.required(row, "row");
		if (row.getRowId() == null) {
			return null;
		}
		return row.getVersionNumber() != null ? String.format(ID_VERSION_TEMPLATE, row.getRowId(), row.getVersionNumber())
				: String.format("%d", row.getRowId());
	}

	/**
	 * Create a new Row given the provided entity id.
	 * @param entityId
	 * @return
	 */
	public static Row createRow(String entityId) {
		if(entityId == null) {
			return new Row();
		}
		String[] split = entityId.split(DELMITER);
		return new Row().setRowId(Long.parseLong(split[0]))
				.setVersionNumber(split.length > 1 ? Long.parseLong(split[1]) : null);
	}

	/**
	 * Create a {@link GenericRecord} object to represent the provided row.
	 * 
	 * @param objectSchema The schema that defines the row.
	 * @param columns      The ColumnModel schema that defines the row.
	 * @param row          The row.
	 * @return
	 */
	public static GenericRecord createObject(Schema objectSchema, List<ColumnModel> columns, Row row) {
		GenericRecordBuilder builder = new GenericRecordBuilder(objectSchema);
		objectSchema.getFields().forEach(f -> {
			Object value = ColumnTypeAvro.matchType(columns.get(f.pos()).getColumnType()).rowToAvro(row.getValues().get(f.pos()));
			builder.set(f,value);
		});
		return builder.build();
	}
}
