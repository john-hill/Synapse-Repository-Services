package org.sagebionetworks.repo.model.grid.node;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.stream.Stream;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;

public class VectorNodeTest {
	private LogicalTimestamp id;
	private LogicalTimestamp id2;
	private LogicalTimestamp id3;

	@BeforeEach
	public void before() {
		id = new LogicalTimestamp().setReplicaId(1L).setSequenceNumber(2L);
		id2 = new LogicalTimestamp().setReplicaId(3L).setSequenceNumber(4L);
		id3 = new LogicalTimestamp().setReplicaId(5L).setSequenceNumber(6L);
	}

	@ParameterizedTest
	@MethodSource("validValues")
	public void testToAndFromJSON(Object value) {
		VectorNode vec = new VectorNode().setId(id).setValues(new LinkedHashMap<>());
		vec.getValues().put("c1", new ConstantNode().setId(id2).setValue(value));
		vec.getValues().put("c2", new ConstantNode().setId(id3).setValue("other value"));
		// call under test
		String json = vec.getValueAsJson();
		// call under test
		VectorNode other = new VectorNode().setId(id).setValueFromJson(json);
		assertEquals(vec, other);
	}

	public static Stream<Object> validValues() {
		Object[] object = { 123, true, false, 4569999999999L, 3.14, "hello", new JSONArray("[1,2,3]"),
				new JSONObject("{\"key\":99}"), null };
		return Stream.of(object);
	}

	@Test
	public void testWithNullMap() {
		VectorNode vec = new VectorNode().setId(id).setValues(null);
		String json = vec.getValueAsJson();
		assertEquals("{}", json);
		VectorNode other = new VectorNode().setId(id).setValueFromJson(json);
		assertEquals(vec, other);
	}

	@Test
	public void testGetValueAsJson() {
		VectorNode vec = new VectorNode().setId(id).setValues(new LinkedHashMap<>());
		vec.getValues().put("c1", new ConstantNode().setId(id2).setValue(new JSONArray("[1,2,3]")));
		vec.getValues().put("c2", new ConstantNode().setId(id3).setValue("other value"));
		String json = vec.getValueAsJson();
		assertEquals("{\"c1\":{\"v\":[1,2,3],\"i\":[3,4]},\"c2\":{\"v\":\"other value\",\"i\":[5,6]}}", json);
		VectorNode other = new VectorNode().setId(id).setValueFromJson(json);
		assertEquals(vec, other);
	}
}
