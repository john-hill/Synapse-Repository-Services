package org.sagebionetworks.repo.model.grid.patch.operation;

import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;

/**
 * Abstraction shared by all patch operations.
 * See: <a href=
 * "https://jsonjoy.com/specs/json-crdt-patch/patch-document/patch-strucure">patch-strucure</a>
 */
public interface Operation {

	OperationType getType();

	/**
	 * The ID of this operation.
	 * 
	 * @return
	 */
	LogicalTimestamp getOperationId();

	/**
	 * The span is the number of cycles consumed by an operation.
	 * 
	 * @return
	 */
	long span();

}
