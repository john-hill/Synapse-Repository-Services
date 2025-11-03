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
    public void testStringToJson() {
        ConValue s = new ConValue(ConType.STRING, "abc");
        // ObjectMapper will produce a JSON string with quotes
        assertEquals("\"abc\"", s.toJson());
    }

    @Test
    public void testStringToJsonWithEscapedQuote() {
        ConValue s = new ConValue(ConType.STRING, "a\"bc");
        // ObjectMapper will produce a JSON string with quotes
        assertEquals("\"a\\\"bc\"", s.toJson());
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
        ConValue v4 = ConValue.fromJsonString("\"he\\\"llo\"");
        assertEquals(ConType.STRING, v4.getType());
        assertEquals("he\"llo", v4.getValue());

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

    enum ConValueTestCase {
        NULL(ConType.NULL, null, "[null]"),
        NULL_WITH_JSON_NULL(ConType.NULL, JSONObject.NULL, "[null]"),
        UNDEFINED(ConType.UNDEFINED, null, "[0,0]"),
        TIMESTAMP(ConType.TIMESTAMP, new LogicalTimestamp().setReplicaId(123L).setSequenceNumber(456L), "[0,[123,456]]"),
        STRING_WITH_NULL_VALUE(ConType.STRING, null, "[null]", new ConValue(ConType.NULL, null)), // null value for STRING becomes NULL type after deserialization
        EMPTY_STRING(ConType.STRING, "", "[\"\"]"),
        STRING(ConType.STRING, "hello", "[\"hello\"]"),
        LONG_ZERO(ConType.LONG, 0L, "[0]"),
        LONG(ConType.LONG, 123L, "[123]"),
        LONG_WITH_INT(ConType.LONG, 4, "[4]"),
        DOUBLE(ConType.DOUBLE, 1.25, "[1.25]"),
        DOUBLE_ZERO(ConType.DOUBLE, 0.0, "[0]"),
        BOOLEAN_TRUE(ConType.BOOLEAN, true, "[true]"),
        BOOLEAN_FALSE(ConType.BOOLEAN, false, "[false]"),
        ARRAY(ConType.JSON_ARRAY, new JSONArray("[1,2,3]"),"[[1,2,3]]"),
        ARRAY_EMPTU(ConType.JSON_ARRAY, new JSONArray("[]"),"[[]]"),
        OBJECT(ConType.JSON_OBJECT, new JSONObject("{\"key\":99}"),"[{\"key\":99}]"),
        OBJECT_EMPTY(ConType.JSON_OBJECT, new JSONObject("{}"),"[{}]");


        ConType type;
        Object value;
        String expectedSerializationValue;
        // Optionally define the expected ConValue after deserialization, if different from the original
        ConValue expectedConValueAfterDeserialize = null;

        ConValueTestCase(ConType type, Object value, String expectedSerializationValue) {
            this.type = type;
            this.value = value;
            this.expectedSerializationValue = expectedSerializationValue;
        }

        ConValueTestCase(ConType type, Object value, String expectedSerializationValue, ConValue expectedConValueAfterDeserialize) {
            this.type = type;
            this.value = value;
            this.expectedSerializationValue = expectedSerializationValue;
            this.expectedConValueAfterDeserialize = expectedConValueAfterDeserialize;
        }
    }

    @ParameterizedTest
    @EnumSource(ConValueTestCase.class)
    public void testGetTypeAndValueFromConTypeRoundTrip(ConValueTestCase testCase) {
        // create a representative value for each supported class
        ConValue v = new ConValue(testCase.type, testCase.value);

        JSONArray compact = v.toCompact();
        assertEquals(testCase.expectedSerializationValue, compact.toString());

        ConValue reconstructed = ConValue.fromCompact(compact);
        ConValue expected = v;
        if (testCase.expectedConValueAfterDeserialize != null) {
            expected = testCase.expectedConValueAfterDeserialize;
        }
        assertEquals(expected, reconstructed);
    }


    @ParameterizedTest
    @EnumSource(value = ConType.class, mode = EnumSource.Mode.EXCLUDE, names = { "NULL", "UNDEFINED" })
    public void testGetTypeAndValueFromJavaClassRoundtrip(ConType expectedType) {
        // create a representative value for each supported class
        Object testVal = getTestValue(expectedType);
        ConValue v = new ConValue(expectedType, testVal);
        assertEquals(expectedType, v.getType());
        assertEquals(testVal instanceof Integer && expectedType == ConType.LONG ? Long.valueOf((Integer) testVal) : testVal,
                v.getValue());

        // Test compact serialization round trip is not lossy
        assertEquals(ConValue.fromCompact(v.toCompact()) ,v);
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
