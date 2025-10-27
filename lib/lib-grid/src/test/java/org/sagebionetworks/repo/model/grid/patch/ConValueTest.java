package org.sagebionetworks.repo.model.grid.patch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.EnumSource.Mode;
import org.sagebionetworks.repo.model.grid.patch.compact.LogicalTimestampCompactSerializable;

public class ConValueTest {

    @Test
    public void testIntegerConvertedToLongInConstructor() {
        ConValue cv = new ConValue(ConType.LONG, Integer.valueOf(123));
        assertEquals(ConType.LONG, cv.getType());
        // the constructor converts Integer -> Long for LONG type
        assertEquals(Long.valueOf(123), cv.getValue());
    }

    @Test
    public void testNullAndUndefinedToJson() {
        ConValue undefined = new ConValue(ConType.UNDEFINED, null);
        assertTrue(undefined.isUndefined());
        assertEquals(null, undefined.toJson());

        ConValue n = new ConValue(ConType.NULL, null);
        assertEquals(ConType.NULL, n.getType());
        assertEquals("null", n.toJson());
    }

    @Test
    public void testStringToJsonUsesQuoting() {
        ConValue s = new ConValue(ConType.STRING, "abc");
        // ObjectMapper will produce a JSON string with quotes
        assertEquals("\"abc\"", s.toJson());
    }

    @Test
    public void testNumberToJsonUsesToString() {
        ConValue l = new ConValue(ConType.LONG, 123L);
        assertEquals("123", l.toJson());

        ConValue d = new ConValue(ConType.DOUBLE, 12.5D);
        // Double.toString
        assertEquals(String.valueOf(12.5D), d.toJson());
    }

    @Test
    public void testFromJsonStringVarious() {
        // null input -> undefined
        ConValue v1 = ConValue.fromJsonString(null);
        assertEquals(ConType.UNDEFINED, v1.getType());
        assertEquals(null, v1.getValue());

        // literal null -> ConType.NULL
        ConValue v2 = ConValue.fromJsonString("null");
        assertEquals(ConType.NULL, v2.getType());
        assertEquals(JSONObject.NULL, v2.getValue());

        // integer number -> LONG and value converted to Long
        ConValue v3 = ConValue.fromJsonString("123");
        assertEquals(ConType.LONG, v3.getType());
        assertEquals(Long.valueOf(123), v3.getValue());

        // string
        ConValue v4 = ConValue.fromJsonString("\"hello\"");
        assertEquals(ConType.STRING, v4.getType());
        assertEquals("hello", v4.getValue());

        // object
        ConValue v5 = ConValue.fromJsonString("{}");
        assertEquals(ConType.JSON_OBJECT, v5.getType());
        assertTrue(v5.getValue() instanceof JSONObject);

        // array
        ConValue v6 = ConValue.fromJsonString("[]");
        assertEquals(ConType.JSON_ARRAY, v6.getType());
        assertTrue(v6.getValue() instanceof JSONArray);
    }

    @Test
    public void testToCompactUndefinedNullAndOther() {
        ConValue undefined = new ConValue(ConType.UNDEFINED, null);
        assertTrue(new JSONArray("[0,0]").similar(undefined.toCompact()));

        ConValue n = new ConValue(ConType.NULL, null);
        assertTrue(new JSONArray("[null]").similar(n.toCompact()));

        ConValue l = new ConValue(ConType.LONG, 123L);
        assertTrue(new JSONArray().put(123L).similar(l.toCompact()));
    }

    @Test
    public void testTimestampToCompactAndFromCompact() {
        LogicalTimestamp ts = new LogicalTimestamp().setReplicaId(10L).setSequenceNumber(20L);
        // use the compact serializer to create the JSONArray timestamp representation
        JSONArray tsArray = LogicalTimestampCompactSerializable.serialize(ts);
        ConValue cv = new ConValue(ConType.TIMESTAMP, tsArray);
        JSONArray compact = cv.toCompact();
        // should be [0, <tsArray>]
        assertEquals(2, compact.length());
        assertEquals(0, compact.getInt(0));
        // second element is the timestamp array
        assertTrue(compact.get(1) instanceof JSONArray);

        // fromCompact should reconstruct a ConValue with a LogicalTimestamp value
        ConValue reconstructed = ConValue.fromCompact(compact);
        assertEquals(ConType.TIMESTAMP, reconstructed.getType());
        assertTrue(reconstructed.getValue() instanceof LogicalTimestamp);
        assertEquals(ts, reconstructed.getValue());
    }

    @Test
    public void testFromCompactPrimitivesAndNull() {
        ConValue vNull = ConValue.fromCompact(new JSONArray("[null]"));
        assertEquals(ConType.NULL, vNull.getType());
        assertEquals(JSONObject.NULL, vNull.getValue());

        ConValue vNum = ConValue.fromCompact(new JSONArray("[123]"));
        assertEquals(ConType.LONG, vNum.getType());
        assertEquals(Long.valueOf(123), vNum.getValue());
    }

    @Test
    public void testFromCompactInvalidThrows() {
        // length 2 but second element not timestamp array
        JSONArray bad = new JSONArray();
        bad.put(0, 0);
        bad.put(1, 1); // not an array
        assertThrows(IllegalArgumentException.class, () -> ConValue.fromCompact(bad));

        // length other than 1 or 2
        assertThrows(IllegalArgumentException.class, () -> ConValue.fromCompact(new JSONArray("[]")));
        assertThrows(IllegalArgumentException.class, () -> ConValue.fromCompact(new JSONArray("[1,2,3]")));
    }

    @Test
    public void testEqualsWithJsonStructures() {
        JSONObject o1 = new JSONObject().put("a", 1);
        JSONObject o2 = new JSONObject().put("a", 1);
        ConValue c1 = new ConValue(ConType.JSON_OBJECT, o1);
        ConValue c2 = new ConValue(ConType.JSON_OBJECT, o2);
        assertEquals(c1, c2);

        JSONArray a1 = new JSONArray().put(0, 1).put(1, 2);
        JSONArray a2 = new JSONArray().put(0, 1).put(1, 2);
        ConValue ca1 = new ConValue(ConType.JSON_ARRAY, a1);
        ConValue ca2 = new ConValue(ConType.JSON_ARRAY, a2);
        assertEquals(ca1, ca2);

        // different values
        ConValue s1 = new ConValue(ConType.STRING, "x");
        ConValue s2 = new ConValue(ConType.STRING, "y");
        assertNotEquals(s1, s2);
    }

    @Test
    @Disabled("org.json does not properly implement hashCode for JSONObject and JSONArray")
    public void testHashCodeWithJsonStructures() {
        JSONObject o1 = new JSONObject().put("a", 1);
        JSONObject o2 = new JSONObject().put("a", 1);
        ConValue c1 = new ConValue(ConType.JSON_OBJECT, o1);
        ConValue c2 = new ConValue(ConType.JSON_OBJECT, o2);
        assertEquals(c1.hashCode(), c2.hashCode());

        JSONArray a1 = new JSONArray().put(0, 1).put(1, 2);
        JSONArray a2 = new JSONArray().put(0, 1).put(1, 2);
        ConValue ca1 = new ConValue(ConType.JSON_ARRAY, a1);
        ConValue ca2 = new ConValue(ConType.JSON_ARRAY, a2);
        assertEquals(ca1.hashCode(), ca2.hashCode());

        // different values
        ConValue s1 = new ConValue(ConType.STRING, "x");
        ConValue s2 = new ConValue(ConType.STRING, "y");
        assertNotEquals(s1.hashCode(), s2.hashCode());
    }

    @ParameterizedTest
    @EnumSource(value = ConType.class, mode = Mode.EXCLUDE, names = { "NULL", "UNDEFINED" })
    public void testGetTypeAndValueRoundtrip(ConType expectedType) {
        // create a representative value for each supported class
        Object testVal = getTestValue(expectedType);
        ConValue v = new ConValue(expectedType, testVal);
        assertEquals(expectedType, v.getType());
        assertEquals(testVal instanceof Integer && expectedType == ConType.LONG ? Long.valueOf((Integer) testVal) : testVal,
                v.getValue());
    }

    private Object getTestValue(ConType type) {
        Class<?>[] classes = type.getSupportedClasses();
        if (classes == null || classes.length == 0) {
            return null;
        }
        Class<?> clazz = classes[0];
        if (Boolean.class.isAssignableFrom(clazz)) {
            return Boolean.TRUE;
        }
        if (Long.class.isAssignableFrom(clazz)) {
            return 123L;
        }
        if (Integer.class.isAssignableFrom(clazz)) {
            return 123; // will be converted to Long for LONG type
        }
        if (Short.class.isAssignableFrom(clazz)) {
            return Short.valueOf((short) 1);
        }
        if (Double.class.isAssignableFrom(clazz)) {
            return 123.0D;
        }
        if (Float.class.isAssignableFrom(clazz)) {
            return 123f;
        }
        if (String.class.isAssignableFrom(clazz)) {
            return "123";
        }
        if (JSONArray.class.isAssignableFrom(clazz)) {
            return new JSONArray();
        }
        if (JSONObject.class.isAssignableFrom(clazz)) {
            return new JSONObject();
        }
        if (LogicalTimestamp.class.isAssignableFrom(clazz)) {
            return new LogicalTimestamp().setReplicaId(1L).setSequenceNumber(2L);
        }
        throw new IllegalStateException("Unsupported class " + clazz + ".");
    }

}
