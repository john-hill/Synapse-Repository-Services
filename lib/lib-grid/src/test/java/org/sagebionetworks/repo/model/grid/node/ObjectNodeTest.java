package org.sagebionetworks.repo.model.grid.node;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;

public class ObjectNodeTest {

	private List<LogicalTimestamp> ids;

	@BeforeEach
	public void before() {

		ids = List.of(new LogicalTimestamp().setReplicaId(1L).setSequenceNumber(2L),
				new LogicalTimestamp().setReplicaId(3L).setSequenceNumber(4L),
				new LogicalTimestamp().setReplicaId(5L).setSequenceNumber(6L));
	}

	@Test
	public void testJsonValue() {
		Map<String, LogicalTimestamp> value = new LinkedHashMap<>();
		value.put("one", ids.get(1));
		value.put("two", ids.get(2));
		// call under test
		ObjectNode obj = new ObjectNode().setId(ids.get(0)).setValue(value);
		String json = obj.getValueAsJson();
		assertEquals("{\"one\":[3,4],\"two\":[5,6]}", json);
		// call under test
		ObjectNode other = new ObjectNode().setId(ids.get(0)).setValueFromJson(json);
		assertEquals(obj, other);
	}

	@Test
	public void testJsonValueWithNullValue() {
		// call under test
		ObjectNode obj = new ObjectNode().setId(ids.get(0)).setValue(null);
		String json = obj.getValueAsJson();
		assertEquals("{}", json);
		// call under test
		ObjectNode other = new ObjectNode().setId(ids.get(0)).setValueFromJson(json);
		assertEquals(obj, other);
	}

}
