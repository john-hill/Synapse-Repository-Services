package org.sagebionetworks.repo.model.grid.encoding;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import org.json.JSONArray;
import org.json.JSONObject;
import org.sagebionetworks.repo.model.grid.ClockTable;
import org.sagebionetworks.repo.model.grid.patch.ConType;
import org.sagebionetworks.repo.model.grid.patch.ConValue;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fasterxml.jackson.dataformat.cbor.CBORGenerator;
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;

public class CBORUtils {

    private static final CBORMapper CBOR_MAPPER = new CBORMapper();
    private static final CBORFactory CBOR_FACTORY = CBOR_MAPPER.getFactory();

    private static final int UNDEFINED_BYTE = 0xF7;

    public static CBORMapper getCBORMapper() {
        return CBOR_MAPPER;
    }

    public static CBORFactory getCBORFactory() {
        return CBOR_FACTORY;
    }



    /**
     * Decode a ConValue from CBOR/binary format.
     *
     * @param in the input stream containing the encoded value
     * @param clockTable the clock table for decoding timestamps
     * @param isTimestamp true if the value is a logical timestamp (indicated by e=1 in node header)
     * @return the decoded ConValue
     */
    public static ConValue decodeConValue(InputStream in, ClockTable clockTable, boolean isTimestamp) {
        // If the node header indicated this is a timestamp (e=1), decode it as such
        if (isTimestamp) {
            try {
                LogicalTimestamp ts = clockTable.decodeTimestamp(in);
                return new ConValue(ConType.TIMESTAMP, ts);
            } catch (IOException e) {
                throw new RuntimeException("Failed to decode timestamp from binary", e);
            }
        }

        // Wrap in BufferedInputStream to support mark/reset if needed
        if (!in.markSupported()) {
            in = new BufferedInputStream(in, 1);
        }

        // Check for CBOR undefined (0xF7) before parsing
        // Jackson treats undefined as null, so we need to detect it manually
        try {
            // An undefined value is a single byte (0xF7)
            in.mark(1);
            int firstByte = in.read();
            if (firstByte == UNDEFINED_BYTE) {
                // CBOR undefined
                return new ConValue(ConType.UNDEFINED, null);
            }
            in.reset();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read from stream", e);
        }

        // Use Jackson CBOR to decode the value
        JsonNode jsonNode;
        try {
            jsonNode = getCBORMapper().readTree(in);
        } catch (IOException e) {
            throw new RuntimeException("Failed to decode ConValue from CBOR", e);
        }

        if (jsonNode.isNull()) {
            return new ConValue(ConType.NULL, null);
        } else if (jsonNode.isBoolean()) {
            return new ConValue(ConType.BOOLEAN, jsonNode.asBoolean());
        } else if (jsonNode.isIntegralNumber()) {
            return new ConValue(ConType.LONG, jsonNode.asLong());
        } else if (jsonNode.isFloatingPointNumber()) {
            return new ConValue(ConType.DOUBLE, jsonNode.asDouble());
        } else if (jsonNode.isTextual()) {
            return new ConValue(ConType.STRING, jsonNode.asText());
        } else if (jsonNode.isArray()) {
            JSONArray jsonArray = new JSONArray();
            for (JsonNode element : jsonNode) {
                jsonArray.put(convertJsonNodeToOrgJsonCompatible(element));
            }
            return new ConValue(ConType.JSON_ARRAY, jsonArray);
        } else if (jsonNode.isObject()) {
            JSONObject jsonObject = new JSONObject();
            jsonNode.properties().forEach(entry -> {
                jsonObject.put(entry.getKey(), convertJsonNodeToOrgJsonCompatible(entry.getValue()));
            });
            return new ConValue(ConType.JSON_OBJECT, jsonObject);
        } else {
            throw new IllegalArgumentException("Unsupported CBOR node type: " + jsonNode.getNodeType());
        }
    }

    /**
     * Convert a Jackson JsonNode to a Java object suitable for org.json types.
     */
    private static Object convertJsonNodeToOrgJsonCompatible(JsonNode node) {
        if (node.isNull()) {
            return JSONObject.NULL;
        } else if (node.isBoolean()) {
            return node.asBoolean();
        } else if (node.isIntegralNumber()) {
            // NOTE: `org.json` creates `Integer` values by default when parsing an array or object (though Long is
            // likely more correct for JavaScript integers). `asInt` here ensures behavior matches (and equality checks
            // work as expected).
            return node.asInt();
        } else if (node.isFloatingPointNumber()) {
            return node.asDouble();
        } else if (node.isTextual()) {
            return node.asText();
        } else if (node.isArray()) {
            JSONArray arr = new JSONArray();
            for (JsonNode element : node) {
                arr.put(convertJsonNodeToOrgJsonCompatible(element));
            }
            return arr;
        } else if (node.isObject()) {
            JSONObject obj = new JSONObject();
            node.properties().forEach(entry -> {
                obj.put(entry.getKey(), convertJsonNodeToOrgJsonCompatible(entry.getValue()));
            });
            return obj;
        }
        return node.asText();
    }


    /**
     * Convert this ConValue to binary representation using CBOR encoding.
     * For TIMESTAMP types, the LogicalTimestamp's binary format is used.
     * For other types, CBOR encoding is used.
     *
     * @return the binary representation of this value
     */
    public static byte[] encodeConValue(ConValue conValue, ClockTable clockTable) {
        ConType type = conValue.getType();
        Object value = conValue.getValue();
        try {
            if (ConType.TIMESTAMP.equals(type)) {
                // For timestamps, use raw encoding (not difference encoding) for indexed format
                return clockTable.encodeTimestamp((LogicalTimestamp) value);
            } else if (ConType.UNDEFINED.equals(type)) {
                // CBOR undefined is encoded as 0xf7
                return new byte[] { (byte) 0xf7 };
            } else {
                // Use CBOR for other types
                try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                     try (CBORGenerator generator = getCBORFactory().createGenerator(baos)){
                        if (ConType.NULL.equals(type)) {
                            generator.writeNull();
                        } else {
                            writeJsonValueToCbor(generator, value);
                        }
                    }
                    return baos.toByteArray();
                }

            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to encode ConValue to binary", e);
        }
    }

    /**
     * Helper method to write a JSON value to CBOR.
     */
    private static void writeJsonValueToCbor(CBORGenerator generator, Object value) throws IOException {
        if (value == null || value == JSONObject.NULL) {
            generator.writeNull();
        } else if (value instanceof Boolean) {
            generator.writeBoolean((Boolean) value);
        } else if (value instanceof Long) {
            generator.writeNumber((Long) value);
        } else if (value instanceof Integer) {
            generator.writeNumber((Integer) value);
        } else if (value instanceof Double) {
            generator.writeNumber((Double) value);
        } else if (value instanceof String) {
            generator.writeString((String) value);
        } else if (value instanceof JSONArray) {
            JSONArray arr = (JSONArray) value;
            generator.writeStartArray(arr, arr.length());
            for (int i = 0; i < arr.length(); i++) {
                writeJsonValueToCbor(generator, arr.get(i));
            }
            generator.writeEndArray();
        } else if (value instanceof JSONObject) {
            JSONObject obj = (JSONObject) value;
            generator.writeStartObject();
            for (String key : obj.keySet()) {
                generator.writeFieldName(key);
                writeJsonValueToCbor(generator, obj.get(key));
            }
            generator.writeEndObject();
        } else {
            generator.writeString(value.toString());
        }
    }


}