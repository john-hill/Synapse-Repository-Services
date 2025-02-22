package org.sagebionetworks.avro.pfb.model;

import java.util.Objects;

import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.specific.SpecificRecordBase;
import org.sagebionetworks.avro.pfb.PFBUtils;

public class Relation extends SpecificRecordBase {

	public static Schema SCHEMA = SchemaBuilder.record("Relation").fields().requiredString("dst_id")
			.requiredString("dst_name").endRecord();

	private String dst_id;
	private String dst_name;

	public String getDst_id() {
		return dst_id;
	}

	public Relation setDst_id(String dst_id) {
		this.dst_id = dst_id;
		return this;
	}

	public String getDst_name() {
		return dst_name;
	}

	public Relation setDst_name(String dst_name) {
		this.dst_name = dst_name;
		return this;
	}

	@Override
	public void put(int i, Object v) {
		switch (i) {
		case 0:
			setDst_id(PFBUtils.createString(v));
			break;
		case 1:
			setDst_name(PFBUtils.createString(v));
			break;
		default:
			throw new IllegalArgumentException("Unknown index: " + i);
		}
	}

	@Override
	public Object get(int i) {
		switch (i) {
		case 0:
			return getDst_id();
		case 1:
			return getDst_name();
		default:
			throw new IllegalArgumentException("Unknown index: " + i);
		}
	}

	@Override
	public Schema getSchema() {
		return SCHEMA;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = super.hashCode();
		result = prime * result + Objects.hash(dst_id, dst_name);
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (!super.equals(obj))
			return false;
		if (getClass() != obj.getClass())
			return false;
		Relation other = (Relation) obj;
		return Objects.equals(dst_id, other.dst_id) && Objects.equals(dst_name, other.dst_name);
	}

	@Override
	public String toString() {
		return "Relation [dst_id=" + dst_id + ", dst_name=" + dst_name + "]";
	}
}
