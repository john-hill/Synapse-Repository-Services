package org.sagebionetworks.avro.pfb.model;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.avro.Schema;
import org.apache.avro.Schema.Type;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.specific.SpecificRecordBase;
import org.sagebionetworks.avro.pfb.PFBUtils;

public class Metadata extends SpecificRecordBase {

	public static Schema SCHEMA = SchemaBuilder.record("Metadata").fields().name("nodes")
			.type(Schema.createArray(Node.SCHEMA)).noDefault().name("misc")
			.type(Schema.createMap(Schema.create(Type.STRING))).noDefault().endRecord();

	private List<Node> nodes;
	private Map<String, String> misc = Collections.emptyMap();

	public Metadata() {
	}

	public List<Node> getNodes() {
		return nodes;
	}

	public Metadata setNodes(List<Node> nodes) {
		this.nodes = PFBUtils.translateGeneric(nodes, Node.class);
		return this;
	}

	public Map<String, String> getMisc() {
		return misc;
	}

	public Metadata setMisc(Map<String, String> misc) {
		this.misc = PFBUtils.createMap(misc);
		return this;
	}

	@Override
	public void put(int i, Object v) {
		switch (i) {
		case 0:
			setNodes((List<Node>) v);
			break;
		case 1:
			setMisc((Map<String, String>) v);
			break;
		default:
			throw new IllegalArgumentException("Unknown index: " + i);
		}

	}

	@Override
	public Object get(int i) {
		switch (i) {
		case 0:
			return getNodes();
		case 1:
			return getMisc();
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
		result = prime * result + Objects.hash(misc, nodes);
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
		Metadata other = (Metadata) obj;
		return Objects.equals(misc, other.misc) && Objects.equals(nodes, other.nodes);
	}

	@Override
	public String toString() {
		return "Metadata [nodes=" + nodes + ", misc=" + misc + "]";
	}

}
