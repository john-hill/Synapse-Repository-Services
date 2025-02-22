package org.sagebionetworks.avro.pfb.model;

import java.util.Map;
import java.util.Objects;

import org.apache.avro.Schema;
import org.apache.avro.Schema.Type;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.specific.SpecificRecordBase;
import org.sagebionetworks.avro.pfb.PFBUtils;

public class Property extends SpecificRecordBase {

	public static Schema SCHEMA = SchemaBuilder.record("Property").fields().requiredString("name")
			.requiredString("ontology_reference").name("values").type(Schema.createMap(Schema.create(Type.STRING)))
			.noDefault().endRecord();

	private String name;
	private String ontology_reference;
	private Map<String, String> values;

	public String getName() {
		return name;
	}

	public Property setName(String name) {
		this.name = name;
		return this;
	}

	public String getOntology_reference() {
		return ontology_reference;
	}

	public Property setOntology_reference(String ontology_reference) {
		this.ontology_reference = ontology_reference;
		return this;
	}

	public Map<String, String> getValues() {
		return PFBUtils.createMap(values);
	}

	public Property setValues(Map<String, String> values) {
		this.values = PFBUtils.createMap(values);
		return this;
	}

	@Override
	public void put(int i, Object v) {
		switch (i) {
		case 0:
			setName(PFBUtils.createString(v));
			break;
		case 1:
			setOntology_reference(PFBUtils.createString(v));
			break;
		case 2:
			setValues((Map<String, String>) v);
			break;
		default:
			throw new IllegalArgumentException("Unknown index: " + i);
		}
	}

	@Override
	public Object get(int i) {
		switch (i) {
		case 0:
			return getName();
		case 1:
			return getOntology_reference();
		case 2:
			return getValues();
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
		result = prime * result + Objects.hash(name, ontology_reference, values);
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
		Property other = (Property) obj;
		return Objects.equals(name, other.name) && Objects.equals(ontology_reference, other.ontology_reference)
				&& Objects.equals(values, other.values);
	}

	@Override
	public String toString() {
		return "Property [name=" + name + ", ontology_reference=" + ontology_reference + ", values=" + values + "]";
	}
}
