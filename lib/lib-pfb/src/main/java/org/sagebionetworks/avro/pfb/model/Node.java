package org.sagebionetworks.avro.pfb.model;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.avro.Schema;
import org.apache.avro.Schema.Type;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.specific.SpecificRecordBase;
import org.sagebionetworks.avro.pfb.PFBUtils;

public class Node extends SpecificRecordBase {

	public static Schema SCHEMA = SchemaBuilder.record("Node").fields().requiredString("name")
			.requiredString("ontology_reference").name("values").type(Schema.createMap(Schema.create(Type.STRING)))
			.noDefault().name("links").type(Schema.createArray(Link.SCHEMA)).noDefault().name("properties")
			.type(Schema.createArray(Property.SCHEMA)).noDefault().endRecord();

	private String name;
	private String ontology_reference = "";
	private Map<String, String> values = Collections.emptyMap();
	private List<Link> links = Collections.emptyList();
	private List<Property> properties = Collections.emptyList();

	public Node() {
	}

	public String getName() {
		return name;
	}

	public Node setName(String name) {
		this.name = name;
		return this;
	}

	public String getOntology_reference() {
		return ontology_reference;
	}

	public Node setOntology_reference(String ontology_reference) {
		this.ontology_reference = ontology_reference;
		return this;
	}

	public Map<String, String> getValues() {
		return values;
	}

	public Node setValues(Map<String, String> values) {
		this.values = PFBUtils.createMap(values);
		return this;
	}

	public List<Link> getLinks() {
		return links;
	}

	public Node setLinks(List<Link> links) {
		this.links = PFBUtils.translateGeneric(links, Link.class);
		return this;
	}

	public List<Property> getProperties() {
		return properties;
	}

	public Node setProperties(List<Property> properties) {
		this.properties = PFBUtils.translateGeneric(properties, Property.class);
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
		case 3:
			setLinks((List<Link>) v);
			break;
		case 4:
			setProperties((List<Property>) v);
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
		case 3:
			return getLinks();
		case 4:
			return getProperties();
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
		result = prime * result + Objects.hash(links, name, ontology_reference, properties, values);
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
		Node other = (Node) obj;
		return Objects.equals(links, other.links) && Objects.equals(name, other.name)
				&& Objects.equals(ontology_reference, other.ontology_reference)
				&& Objects.equals(properties, other.properties) && Objects.equals(values, other.values);
	}

	@Override
	public String toString() {
		return "Node [name=" + name + ", ontology_reference=" + ontology_reference + ", values=" + values + ", links="
				+ links + ", properties=" + properties + "]";
	}
}
