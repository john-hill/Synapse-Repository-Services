package org.sagebionetworks.avro.pfb.model;

import java.util.Arrays;
import java.util.Objects;

import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.specific.SpecificRecordBase;
import org.sagebionetworks.avro.pfb.PFBUtils;

public class Link extends SpecificRecordBase {

	public static Schema SCHEMA = SchemaBuilder.record("Link").fields().name("multiplicity")
			.type(Schema.createEnum("Multiplicity", null, null,
					Arrays.asList("ONE_TO_ONE", "ONE_TO_MANY", "MANY_TO_ONE", "MANY_TO_MANY")))
			.noDefault().requiredString("dst").requiredString("name").endRecord();

	private Multiplicity multiplicity;
	private String dst;
	private String name;

	public Multiplicity getMultiplicity() {
		return multiplicity;
	}

	public Link setMultiplicity(Multiplicity multiplicity) {
		this.multiplicity = multiplicity;
		return this;
	}

	public String getDst() {
		return dst;
	}

	public Link setDst(String dst) {
		this.dst = dst;
		return this;
	}

	public String getName() {
		return name;
	}

	public Link setName(String name) {
		this.name = name;
		return this;
	}

	@Override
	public void put(int i, Object v) {
		switch (i) {
		case 0:
			setMultiplicity(v != null ? Multiplicity.valueOf(v.toString()) : null);
			break;
		case 1:
			setDst(PFBUtils.createString(v));
			break;
		case 2:
			setName(PFBUtils.createString(v));
			break;
		default:
			throw new IllegalArgumentException("Unknown index: " + i);
		}
	}

	@Override
	public Object get(int i) {
		switch (i) {
		case 0:
			return getMultiplicity();
		case 1:
			return getDst();
		case 2:
			return getName();
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
		result = prime * result + Objects.hash(dst, multiplicity, name);
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
		Link other = (Link) obj;
		return Objects.equals(dst, other.dst) && multiplicity == other.multiplicity && Objects.equals(name, other.name);
	}

	@Override
	public String toString() {
		return "Link [multiplicity=" + multiplicity + ", dst=" + dst + ", name=" + name + "]";
	}
}
