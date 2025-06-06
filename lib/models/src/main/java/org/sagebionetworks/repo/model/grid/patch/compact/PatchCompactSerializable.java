package org.sagebionetworks.repo.model.grid.patch.compact;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.json.JSONArray;
import org.json.JSONObject;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;
import org.sagebionetworks.repo.model.grid.patch.Patch;
import org.sagebionetworks.repo.model.grid.patch.operation.Operation;
import org.sagebionetworks.repo.model.grid.patch.operation.OperationType;
import org.sagebionetworks.util.ValidateArgument;

public class PatchCompactSerializable {

	private static List<OperationSerializable<?>> serializables = Arrays.asList(new NewConstantSerializable());
	private static Map<OperationType, OperationSerializable<?>> map = serializables.stream()
			.collect(Collectors.toMap(OperationSerializable::getType, handler -> handler));

	/**
	 * Deserialize a {@link Patch} from a compact JSON array.
	 * 
	 * @param compact
	 * @return
	 */
	public static Patch deserialize(JSONArray compact) {
		Patch patch = new Patch();
		// first array is the patch metadata
		JSONArray header = compact.getJSONArray(0);

		patch.setPatchId(deserializeLogicalTimestamp(header.getJSONArray(0)));
		JSONObject metadata = header.optJSONObject(1);
		patch.setMetadta(metadata != null ? metadata.toString() : null);
		List<Operation> operations = new ArrayList<>(compact.length() - 1);
		for (int i = 0; i < compact.length() - 1; i++) {
			JSONArray next = compact.getJSONArray(i + 1);
			int code = next.getInt(0);
			OperationType type = OperationType.fromCode(code);
			OperationSerializable<?> serializer = map.get(type);
			if (serializer == null) {
				throw new IllegalArgumentException("Unknown type: " + type);
			}
			operations.add(serializer.deserialize(patch.getPatchId(), i, next));
		}
		patch.setOperations(operations);
		return patch;
	}

	/**
	 * Read the patch ID for a patch without deserialization.
	 * @return
	 */
	public static LogicalTimestamp peekPatchId(JSONArray compact) {
		ValidateArgument.required(compact, "array");
		JSONArray header = compact.getJSONArray(0);
		return deserializeLogicalTimestamp(header.getJSONArray(0));
	}

	/**
	 * Serialize a {@link Patch} to a compact JSON array.
	 * 
	 * @param patch
	 * @return
	 */
	public static JSONArray serialize(Patch patch) {
		JSONArray compact = new JSONArray();
		JSONArray header = new JSONArray().put(0, serializeLogicalTimestamp(patch.getPatchId()));
		if (patch.getMetadta() != null) {
			header.put(1, new JSONObject(patch.getMetadta()));
		}
		compact.put(0, header);
		for (int i = 0; i < patch.getOperations().size(); i++) {
			Operation op = patch.getOperations().get(i);
			OperationSerializable<?> serializer = map.get(op.getType());
			if (serializer == null) {
				throw new IllegalArgumentException("Unknown type: " + op.getType());
			}

			JSONArray serializedOperation = serializeWithHelper(serializer, patch.getPatchId(), i, op);
			compact.put(serializedOperation);
		}

		return compact;
	}

	/**
	 * Catpures the generic of OperationSerializable and serializes.
	 * 
	 * @param <S>
	 * @param typedSerializer
	 * @param patchId
	 * @param index
	 * @param generalOperation
	 * @return
	 */
	private static <S extends Operation> JSONArray serializeWithHelper(OperationSerializable<S> typedSerializer,
			LogicalTimestamp patchId, int index, Operation generalOperation) {
		S specificOperation = typedSerializer.getTypeClass().cast(generalOperation);
		return typedSerializer.serialize(patchId, index, specificOperation);
	}

	public static LogicalTimestamp deserializeLogicalTimestamp(JSONArray array) {
		return new LogicalTimestamp().setReplicaId(array.getLong(0)).setSequenceNumber(array.getLong(1));
	}

	public static JSONArray serializeLogicalTimestamp(LogicalTimestamp time) {
		return new JSONArray().put(0, time.getReplicaId()).put(1, time.getSequenceNumber());
	}

}
