package org.sagebionetworks.repo.model.grid.patch;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;

import org.json.JSONArray;
import org.junit.jupiter.api.Test;
import org.sagebionetworks.repo.model.grid.patch.compact.PatchCompactSerializable;
import org.sagebionetworks.repo.model.grid.patch.operation.NewConstant;

public class PatchCompactSerializableTest {

	@Test
	public void testDeserializeAndSerialize() {
		String patchJson = "[[[4,10],{\"key\":9}],[0]]";

		// call under test
		Patch patch = PatchCompactSerializable.deserialize(new JSONArray(patchJson));
		Patch expected = new Patch().setMetadta("{\"key\":9}")
				.setPatchId(new LogicalTimestamp().setReplicaId(4L).setSequenceNumber(10L)).setOperations(Arrays.asList(
						new NewConstant().setId(new LogicalTimestamp().setReplicaId(4L).setSequenceNumber(10L))));
		assertEquals(expected, patch);

		// call under test
		String reSerialized = PatchCompactSerializable.serialize(patch).toString();
		assertEquals(patchJson, reSerialized);
	}

	@Test
	public void test() {
		String patchJson = "[[[4,10],{\"key\":9}],[0]]";
		// call under test
		LogicalTimestamp patchId = PatchCompactSerializable.peekPatchId(new JSONArray(patchJson));
		LogicalTimestamp expected = new LogicalTimestamp().setReplicaId(4L).setSequenceNumber(10L);
		assertEquals(patchId, expected);
	}

}
