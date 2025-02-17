package org.sagebionetworks.avro.pfb;

import static org.junit.Assert.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.avro.Schema;
import org.apache.avro.Schema.Type;
import org.apache.avro.file.DataFileReader;
import org.apache.avro.file.DataFileWriter;
import org.apache.avro.file.SeekableByteArrayInput;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.GenericRecordBuilder;
import org.apache.avro.io.DatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.commons.io.IOUtils;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.sagebionetworks.avro.pfb.model.Entity;
import org.sagebionetworks.avro.pfb.model.Link;
import org.sagebionetworks.avro.pfb.model.Metadata;
import org.sagebionetworks.avro.pfb.model.Multiplicity;
import org.sagebionetworks.avro.pfb.model.Node;
import org.sagebionetworks.avro.pfb.model.Property;
import org.sagebionetworks.avro.pfb.model.Relation;

public class PFBUtilsTest {

	/**
	 * Test that we can create a minimal schema that matches the provided
	 * minimal_data.avro file's schema.
	 * 
	 * @throws IOException
	 */
	@Test
	public void testMinimalSchema() throws IOException {

		Schema expected = loadAvroSchema(loadFromClasspathAsBytes("minimal_data.avro"));
		assertNotNull(expected);

		// extract all of non-Metadata schema from the object's union.
		List<Schema> nonMetaDataSchemas = expected.getField("object").schema().getTypes().stream()
				.filter(s -> (!s.getName().equals("Metadata"))).collect(Collectors.toList());

		// call under test
		Schema result = Entity.createEntitySchema(nonMetaDataSchemas);
		assertEquals(expected, result);

	}

	/**
	 * This test ensure that we can create an avro file that matches the
	 * minimal_data.avro file.
	 * 
	 * @throws IOException
	 */
	@Test
	public void testWriteAndReadMinimalData() throws IOException {
		byte[] minimalBytes = loadFromClasspathAsBytes("minimal_data.avro");
		Schema minimalSchema = loadAvroSchema(minimalBytes);
		assertNotNull(minimalSchema);

		// Write a new PFB file to this byte array using Entity classes.
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		try (DataFileWriter<Entity> writer = new DataFileWriter<>(new SpecificDatumWriter<>(minimalSchema))) {
			writer.create(minimalSchema, bos);
			// call under test
			writer.append(new Entity(minimalSchema).setId(null).setName("Metadata").setObject(createMinimalMetadata()));
			// call under test
			writer.append(new Entity(minimalSchema).setId("HG01101_cram").setName("submitted_aligned_reads")
					.setObject(createMinimalData(minimalSchema)));
		}

		List<String> expected = loadAvroAsStrings(minimalBytes);
		List<String> resutls = loadAvroAsStrings(bos.toByteArray());
		assertEquals(expected, resutls);
	}

	@Test
	public void testWriteAndReadAllEntity() throws IOException {
		Metadata meta = new Metadata().setNodes(Arrays.asList(createNode(0), createNode(1)))
				.setMisc(createMap("misc", 0));
		// for this case we do not have any custom schemas.
		Entity entity = new Entity(Entity.createEntitySchema(Collections.emptyList())).setId("123").setName("Metadata")
				.setObject(meta).setRelations(Arrays.asList(createRelation(0), createRelation(1)));

		// Write the entity metadata row
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		try (DataFileWriter<Entity> writer = new DataFileWriter<>(new SpecificDatumWriter<>(entity.getSchema()))) {
			writer.create(entity.getSchema(), bos);
			// call under test
			writer.append(entity);
		}

		// Read back the entity from the Avro bytes.
		Entity result = null;
		GenericDatumReader<GenericRecord> entityReader = new GenericDatumReader<GenericRecord>(entity.getSchema());
		try (DataFileReader<GenericRecord> dataFileReader = new DataFileReader<>(
				new SeekableByteArrayInput(bos.toByteArray()), entityReader);) {
			result = new Entity(entity.getSchema(), dataFileReader.next());
		}
		assertEquals(entity, result);
	}

	@Test
	public void testCreateSpecificRecord() {
		GenericRecord g = new GenericRecordBuilder(Relation.SCHEMA).set("dst_id", "id").set("dst_name", "name").build();

		// Call under test
		Relation r = PFBUtils.createSpecificRecord(g, Relation.class);
		assertEquals(new Relation().setDst_id("id").setDst_name("name"), r);

	}

	@Test
	public void testCreateSpecificRecordWithNullRecord() {
		GenericRecord g = null;

		// Call under test
		Relation r = PFBUtils.createSpecificRecord(g, Relation.class);
		assertNull(r);
	}

	@Test
	public void testCreateSpecificRecordWithNullClass() {
		GenericRecord g = new GenericRecordBuilder(Relation.SCHEMA).set("dst_id", "id").set("dst_name", "name").build();

		String message = assertThrows(IllegalArgumentException.class, () -> {
			// Call under test
			Relation r = PFBUtils.createSpecificRecord(g, null);
			assertNull(r);
		}).getMessage();
		assertEquals("Clazz is required.", message);
	}

	@Test
	public void testTranslateGeneric() {
		GenericRecord g = new GenericRecordBuilder(Relation.SCHEMA).set("dst_id", "id").set("dst_name", "name").build();
		Relation r = new Relation().setDst_id("id2").setDst_name("name2");
		List<?> in = Arrays.asList(g, r);
		// call under test
		List<Relation> results = PFBUtils.translateGeneric(in, Relation.class);
		List<Relation> expected = Arrays.asList(new Relation().setDst_id("id").setDst_name("name"), r);
		assertEquals(expected, results);
	}

	@Test
	public void testTranslateGenericWithNullList() {
		List<?> in = null;
		// call under test
		List<Relation> result = PFBUtils.translateGeneric(in, Relation.class);
		assertNull(result);
	}

	@Test
	public void testTranslateGenericWithNullClass() {
		GenericRecord g = new GenericRecordBuilder(Relation.SCHEMA).set("dst_id", "id").set("dst_name", "name").build();
		Relation r = new Relation().setDst_id("id2").setDst_name("name2");
		List<?> in = Arrays.asList(g, r);
		String message = assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			PFBUtils.translateGeneric(in, null);
		}).getMessage();
		assertEquals("Clazz is required.", message);
	}

	@Test
	public void testCreateString() {
		assertEquals("one", PFBUtils.createString("one"));
		assertEquals("abc", PFBUtils.createString(new StringBuilder("abc")));
		assertEquals(null, PFBUtils.createString(null));
		assertEquals("123", PFBUtils.createString(new StringBuffer("123")));
	}

	static Relation createRelation(int i) {
		return new Relation().setDst_id("id" + i).setDst_name("name" + i);
	}

	static Node createNode(int i) {
		return new Node().setName("nodeName" + i).setOntology_reference("nodeOnt" + i)
				.setValues(createMap("nodeVal", i))
				.setLinks(
						Arrays.asList(createLink(0, Multiplicity.MANY_TO_ONE), createLink(1, Multiplicity.ONE_TO_ONE)))
				.setProperties(Arrays.asList(createProperty(0), createProperty(1)));
	}

	static Link createLink(int i, Multiplicity mult) {
		return new Link().setName("linkName" + i).setDst("linkDist" + i).setMultiplicity(mult);
	}

	static Property createProperty(int i) {
		return new Property().setName("propName" + i).setOntology_reference("propOnt" + i)
				.setValues(createMap("propVal", i));
	}

	static Map<String, String> createMap(String k, int i) {
		Map<String, String> map = new HashMap<>();
		map.put("key0" + k + i, "val0" + k + i);
		map.put("key1" + k + i, "val1" + k + i);
		return map;
	}

	/**
	 * Create a Metadata object that matches the Metadata row from the
	 * minimal_data.avro file.
	 * 
	 * @return
	 */
	public static Metadata createMinimalMetadata() {
		return new Metadata().setNodes(Arrays.asList(new Node().setName("root"),
				new Node().setName("data_release")
						.setLinks(Arrays.asList(
								new Link().setMultiplicity(Multiplicity.MANY_TO_ONE).setDst("root").setName("roots"))),
				new Node().setName("submitted_aligned_reads")));
	}

	/**
	 * Builder a GenericRecord that matches the submitted_aligned_reads row from the
	 * minimal_data.avro file.
	 * 
	 * @param minimalSchema
	 * @return
	 * @throws JSONException
	 * @throws IOException
	 */
	GenericRecord createMinimalData(Schema minimalSchema) throws JSONException, IOException {
		Schema readsSchema = minimalSchema.getField("object").schema().getTypes().stream()
				.filter(s -> (s.getName().equals("submitted_aligned_reads"))).findFirst().get();

		JSONObject object = new JSONObject(
				new String(loadFromClasspathAsBytes("submitted_aligned_reads.json"), StandardCharsets.UTF_8));
		GenericRecordBuilder builder = new GenericRecordBuilder(readsSchema);

		readsSchema.getFields().forEach(f -> {

			// Enums require special treatment.
			List<Schema> enumSchemas = f.schema().getTypes().stream().filter(t -> Type.ENUM.equals(t.getType()))
					.collect(Collectors.toList());

			Object value = object.get(f.name());
			if (value instanceof Integer) {
				// Schemas expect longs even if an integer was read.
				value = ((Integer) value).longValue();
			}
			if (JSONObject.NULL == value) {
				// JSON null must be treated as a Java null.
				value = null;
			}
			if (!enumSchemas.isEmpty()) {
				enumSchemas.forEach(s -> {
					if (s.getEnumSymbols().contains(object.get(f.name()))) {
						builder.set(f, new GenericData.EnumSymbol(s, object.get(f.name())));
					}
				});
			} else {
				builder.set(f, value);
			}
		});

		return builder.build();
	}

	/**
	 * Load a file from the class path as a binary byte array.
	 * 
	 * @param fileName
	 * @return
	 * @throws IOException
	 */
	public static byte[] loadFromClasspathAsBytes(String fileName) throws IOException {
		try (InputStream in = PFBUtilsTest.class.getClassLoader().getResourceAsStream(fileName)) {
			if (in == null) {
				throw new IllegalArgumentException("Cannot find file " + fileName + " on classpath.");
			}
			return IOUtils.toByteArray(in);
		}
	}

	/**
	 * Helper to load an avro schema from a binary avaro file.
	 * 
	 * @param bytes
	 * @return
	 * @throws IOException
	 */
	public Schema loadAvroSchema(byte[] bytes) throws IOException {
		try (DataFileReader<GenericRecord> dataFileReader = new DataFileReader<>(new SeekableByteArrayInput(bytes),
				new GenericDatumReader<>());) {
			return dataFileReader.getSchema();
		}
	}

	/**
	 * Load an avro file as a list of JSON strings. The first string will be the
	 * schema. All other strings will be the rows.
	 * 
	 * @param bytes
	 * @return
	 * @throws IOException
	 */
	public List<String> loadAvroAsStrings(byte[] bytes) throws IOException {
		List<String> data = new ArrayList<>();
		DatumReader<GenericRecord> datumReader = new GenericDatumReader<>();
		try (DataFileReader<GenericRecord> dataFileReader = new DataFileReader<>(new SeekableByteArrayInput(bytes),
				datumReader);) {
			data.add(dataFileReader.getSchema().toString());
			dataFileReader.forEach(r -> {
				data.add(r.toString());
			});
		}
		return data;
	}
}
