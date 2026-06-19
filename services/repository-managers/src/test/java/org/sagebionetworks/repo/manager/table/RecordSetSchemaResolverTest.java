package org.sagebionetworks.repo.manager.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.manager.EntityManager;
import org.sagebionetworks.repo.manager.schema.JsonSchemaManager;
import org.sagebionetworks.repo.model.file.FileHandle;
import org.sagebionetworks.repo.model.file.S3FileHandle;
import org.sagebionetworks.repo.model.schema.JsonSchema;
import org.sagebionetworks.repo.model.schema.JsonSchemaObjectBinding;
import org.sagebionetworks.repo.model.schema.JsonSchemaVersionInfo;
import org.sagebionetworks.repo.model.schema.Type;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.repo.model.table.CsvTableDescriptor;

@ExtendWith(MockitoExtension.class)
public class RecordSetSchemaResolverTest {

	@Mock
	private EntityManager mockEntityManager;
	@Mock
	private JsonSchemaManager mockJsonSchemaManager;

	@Spy
	@InjectMocks
	private RecordSetSchemaResolver resolver;

	private FileHandle fileHandle;
	private CsvTableDescriptor csvDescriptor;
	private String entityId;

	@BeforeEach
	public void before() {
		fileHandle = new S3FileHandle().setId("456");
		csvDescriptor = new CsvTableDescriptor().setIsFirstLineHeader(true);
		entityId = "syn123";
	}

	private void stubInferSchema(List<ColumnModel> columns) {
		doReturn(columns).when(resolver).inferSchemaFromCsv(any(FileHandle.class), any(CsvTableDescriptor.class),
				anyBoolean());
	}

	private void stubBoundSchema(JsonSchema schema) {
		String schemaId = "my.org-Schema-1.0.0";
		JsonSchemaObjectBinding binding = new JsonSchemaObjectBinding()
				.setJsonSchemaVersionInfo(new JsonSchemaVersionInfo().set$id(schemaId));
		when(mockEntityManager.findBoundSchema(entityId)).thenReturn(Optional.of(binding));
		when(mockJsonSchemaManager.getValidationSchema(schemaId)).thenReturn(schema);
	}

	@Test
	public void testGetReconciledSchemaWithNoBoundSchema() {
		stubInferSchema(List.of(
				new ColumnModel().setName("a").setColumnType(ColumnType.INTEGER),
				new ColumnModel().setName("b").setColumnType(ColumnType.STRING)));
		when(mockEntityManager.findBoundSchema(entityId)).thenReturn(Optional.empty());

		// call under test
		RecordSetSchemaResolver.ReconciledSchema result = resolver.getReconciledSchema(entityId, fileHandle,
				csvDescriptor, false);
		List<ColumnModel> schema = result.getSchema();

		assertEquals(2, schema.size());
		assertEquals("a", schema.get(0).getName());
		assertEquals(ColumnType.INTEGER, schema.get(0).getColumnType());
		assertEquals("b", schema.get(1).getName());
		assertEquals(ColumnType.STRING, schema.get(1).getColumnType());
		assertEquals(0, result.getRequiredColumnIndices().size());
	}

	@Test
	public void testGetReconciledSchemaUpgradesArrayColumnToList() {
		// The bound JSON Schema declares "tags" as an array, so the scalar STRING
		// inferred from the CSV must be upgraded to STRING_LIST. "a" stays scalar.
		stubInferSchema(List.of(
				new ColumnModel().setName("a").setColumnType(ColumnType.INTEGER),
				new ColumnModel().setName("tags").setColumnType(ColumnType.STRING)));
		JsonSchema validationSchema = new JsonSchema().setProperties(Map.of(
				"tags", new JsonSchema().setType(Type.array)));
		stubBoundSchema(validationSchema);

		// call under test
		RecordSetSchemaResolver.ReconciledSchema result = resolver.getReconciledSchema(entityId, fileHandle,
				csvDescriptor, false);
		List<ColumnModel> schema = result.getSchema();

		assertEquals(2, schema.size());
		assertEquals("a", schema.get(0).getName());
		assertEquals(ColumnType.INTEGER, schema.get(0).getColumnType());
		assertEquals("tags", schema.get(1).getName());
		assertEquals(ColumnType.STRING_LIST, schema.get(1).getColumnType());
	}

	@Test
	public void testGetReconciledSchemaWithRequiredIndices() {
		stubInferSchema(List.of(
				new ColumnModel().setName("a").setColumnType(ColumnType.INTEGER),
				new ColumnModel().setName("b").setColumnType(ColumnType.STRING),
				new ColumnModel().setName("c").setColumnType(ColumnType.INTEGER)));
		JsonSchema validationSchema = new JsonSchema()
				.setProperties(Map.of("a", new JsonSchema(), "c", new JsonSchema()))
				.setRequired(List.of("a", "c"));
		stubBoundSchema(validationSchema);

		// call under test
		RecordSetSchemaResolver.ReconciledSchema result = resolver.getReconciledSchema(entityId, fileHandle,
				csvDescriptor, false);

		assertEquals(List.of("a", "b", "c"),
				result.getSchema().stream().map(ColumnModel::getName).collect(java.util.stream.Collectors.toList()));
		// "a" is index 0, "c" is index 2 in the inferred schema.
		assertEquals(List.of(0, 2), result.getRequiredColumnIndices());
	}

	@Test
	public void testGetReconciledSchemaWithRequiredIndicesAndNoBoundSchema() {
		stubInferSchema(List.of(
				new ColumnModel().setName("a").setColumnType(ColumnType.INTEGER),
				new ColumnModel().setName("b").setColumnType(ColumnType.STRING)));
		when(mockEntityManager.findBoundSchema(entityId)).thenReturn(Optional.empty());

		// call under test
		RecordSetSchemaResolver.ReconciledSchema result = resolver.getReconciledSchema(entityId, fileHandle,
				csvDescriptor, false);

		assertEquals(2, result.getSchema().size());
		assertTrue(result.getRequiredColumnIndices().isEmpty());
	}

	@Test
	public void testGetReconciledSchemaWithFullScanFalsePassesFlagThrough() {
		stubInferSchema(List.of(new ColumnModel().setName("value").setColumnType(ColumnType.INTEGER)));
		when(mockEntityManager.findBoundSchema(entityId)).thenReturn(Optional.empty());

		// call under test
		resolver.getReconciledSchema(entityId, fileHandle, csvDescriptor, false);

		verify(resolver).inferSchemaFromCsv(fileHandle, csvDescriptor, false);
	}

	@Test
	public void testGetReconciledSchemaWithFullScanTruePassesFlagThrough() {
		stubInferSchema(List.of(new ColumnModel().setName("value").setColumnType(ColumnType.DOUBLE)));
		when(mockEntityManager.findBoundSchema(entityId)).thenReturn(Optional.empty());

		// call under test
		resolver.getReconciledSchema(entityId, fileHandle, csvDescriptor, true);

		verify(resolver).inferSchemaFromCsv(fileHandle, csvDescriptor, true);
	}
}
