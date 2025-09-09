package org.sagebionetworks.repo.manager.grid.internal.replica.change;

import java.util.Map;

import org.sagebionetworks.repo.model.grid.patch.ConType;
import org.sagebionetworks.repo.model.grid.patch.ConValue;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;
import org.sagebionetworks.repo.model.grid.patch.operation.builder.Operations;
import org.springframework.stereotype.Component;

@Component
public class UpdateMetadataChangeHandler implements ChangeHandler<UpdateMetadataChange> {

	@Override
	public IntendedChangeType getType() {
		return IntendedChangeType.update_row_metadata;
	}

	@Override
	public void handleChange(PatchBuilder builder, UpdateMetadataChange change) {
		LogicalTimestamp metadataRefId = change.getRowMetadataId();

		// The metadata object might not be there at all for a row without a validation state nor 
		// synapse metadata (For example when the grid is created from a recordSet) 
		if (metadataRefId == null) {
			metadataRefId = builder.addOperationBuilder(Operations.newObject());
			
			// We also need to add the reference in the row object
			builder.addOperationBuilder(Operations.insertObject()
				.setObjectId(change.getRowObjectId())
				.setMap(Map.of("metadata", metadataRefId))
			);
		}

		LogicalTimestamp validationStateRefId = builder.addOperationBuilder(
			Operations.newConstant().setValue(new ConValue(ConType.JSON_OBJECT, change.getValidationState()))
		);
		
		builder.addOperationBuilder(Operations.insertObject()
			.setObjectId(metadataRefId)
			.setMap(Map.of("rowValidation", validationStateRefId))
		);
	}

}
