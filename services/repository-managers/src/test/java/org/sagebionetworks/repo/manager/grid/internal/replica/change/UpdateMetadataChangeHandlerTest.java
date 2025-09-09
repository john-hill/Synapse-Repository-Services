package org.sagebionetworks.repo.manager.grid.internal.replica.change;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.model.grid.patch.ConType;
import org.sagebionetworks.repo.model.grid.patch.ConValue;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;
import org.sagebionetworks.repo.model.grid.patch.operation.builder.InsertObjectBuilder;
import org.sagebionetworks.repo.model.grid.patch.operation.builder.NewConstantBuilder;
import org.sagebionetworks.repo.model.grid.patch.operation.builder.NewObjectBuilder;
import org.sagebionetworks.repo.model.grid.patch.operation.builder.Operations;
import org.sagebionetworks.repo.model.schema.ValidationResults;
import org.sagebionetworks.schema.adapter.JSONObjectAdapterException;
import org.sagebionetworks.schema.adapter.org.json.EntityFactory;

@ExtendWith(MockitoExtension.class)
public class UpdateMetadataChangeHandlerTest {

	@InjectMocks
	private UpdateMetadataChangeHandler handler;
	
	@Mock
	private PatchBuilder mockPatchBuilder;
	
	private UpdateMetadataChange change;
	
	@BeforeEach
	public void before() throws JSONObjectAdapterException {
		JSONObject validationState = EntityFactory.createJSONObjectForEntity(new ValidationResults().setIsValid(true));
	
		change = new UpdateMetadataChange()
			.setRowObjectId(new LogicalTimestamp().setReplicaId(5L).setSequenceNumber(2L))
			.setRowMetadataId(new LogicalTimestamp().setReplicaId(5L).setSequenceNumber(4L))
			.setValidationState(validationState);
	}
	
	@Test
	public void testGetType() {
		assertEquals(IntendedChangeType.update_row_metadata, handler.getType());
	}

	@Test
	public void testHandleChange() throws Exception {
		LogicalTimestamp stateConstRefId = new LogicalTimestamp().setReplicaId(5L).setSequenceNumber(5L);
		
		when(mockPatchBuilder.addOperationBuilder(any(NewConstantBuilder.class)))
			.thenReturn(stateConstRefId);
		
		// Call under test
		handler.handleChange(mockPatchBuilder, change);

		verify(mockPatchBuilder).addOperationBuilder(
			Operations.newConstant().setValue(new ConValue(ConType.JSON_OBJECT, change.getValidationState()))
		);

		verify(mockPatchBuilder).addOperationBuilder(Operations.insertObject()
			.setObjectId(change.getRowMetadataId())
			.setMap(java.util.Map.of("rowValidation", stateConstRefId))
		);
		
		verifyNoMoreInteractions(mockPatchBuilder);
	}
	
	@Test
	public void testHandleChangeWithNoMetadata() throws Exception {
		change.setRowMetadataId(null);
		
		LogicalTimestamp metadataRefId = new LogicalTimestamp().setReplicaId(5L).setSequenceNumber(5L);
		
		when(mockPatchBuilder.addOperationBuilder(any(NewObjectBuilder.class)))
			.thenReturn(metadataRefId);
		
		when(mockPatchBuilder.addOperationBuilder(any(InsertObjectBuilder.class)))
			.thenReturn(new LogicalTimestamp().setReplicaId(5L).setSequenceNumber(6L));
		
		LogicalTimestamp stateConstRefId = new LogicalTimestamp().setReplicaId(5L).setSequenceNumber(7L);
		
		when(mockPatchBuilder.addOperationBuilder(any(NewConstantBuilder.class)))
			.thenReturn(stateConstRefId);
		
		// Call under test
		handler.handleChange(mockPatchBuilder, change);
		
		verify(mockPatchBuilder).addOperationBuilder(Operations.insertObject()
			.setObjectId(change.getRowObjectId())
			.setMap(java.util.Map.of("metadata", metadataRefId))
		);

		verify(mockPatchBuilder).addOperationBuilder(
			Operations.newConstant().setValue(new ConValue(ConType.JSON_OBJECT, change.getValidationState()))
		);

		verify(mockPatchBuilder).addOperationBuilder(Operations.insertObject()
			.setObjectId(metadataRefId)
			.setMap(java.util.Map.of("rowValidation", stateConstRefId))
		);
		
		verifyNoMoreInteractions(mockPatchBuilder);
	}

}
