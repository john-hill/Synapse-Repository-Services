package org.sagebionetworks.repo.manager.grid.synch;

import java.util.List;

import org.sagebionetworks.repo.manager.grid.internal.replica.change.IntendedChangePublisher;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.Column;
import org.sagebionetworks.repo.manager.grid.synch.core.SynchronizationLogic;
import org.sagebionetworks.repo.manager.grid.synch.handler.CopyHandler;
import org.sagebionetworks.repo.manager.grid.synch.handler.SourceHandler;
import org.sagebionetworks.repo.manager.grid.synch.io.RowSourceItemReader;
import org.sagebionetworks.repo.manager.grid.synch.row.RowCopy;
import org.sagebionetworks.repo.manager.grid.synch.row.RowMerge;
import org.sagebionetworks.repo.manager.grid.synch.row.RowSource;
import org.sagebionetworks.repo.manager.grid.synch.schema.SchemaCopy;
import org.sagebionetworks.repo.manager.grid.synch.schema.SchemaSource;

/**
 * Factory interface for creating synchronization components (Copy, Source, and
 * Merge implementations) used during grid synchronization. This provider
 * abstracts the creation of concrete implementations, allowing for dependency
 * injection and testability.
 */
public interface SynchronizeProvider {

	/**
	 * Creates a Copy implementation for schema synchronization during Phase 1.
	 *
	 * @param intendedChangePublisher publisher for recording intended schema
	 *                                changes to the copy
	 * @param reader                  handler providing access to the copy's current
	 *                                schema
	 * @return a SchemaCopy instance for synchronizing schema columns
	 */
	SchemaCopy getSchemaCopy(IntendedChangePublisher intendedChangePublisher, CopyHandler reader);

	/**
	 * Creates a Source implementation for schema synchronization during Phase 1.
	 *
	 * @param handler handler providing access to the source's current schema
	 * @return a SchemaSource instance for synchronizing schema columns
	 */
	SchemaSource getSchemaSource(SourceHandler handler);

	/**
	 * Creates a Source implementation for schema synchronization over an explicit
	 * set of source column names (rather than the handler's reported schema). Used
	 * to present the union of the resolved source columns and the grid's existing
	 * columns, so that no grid column is dropped during schema synchronization.
	 *
	 * @param handler           handler for applying schema changes to the source
	 * @param sourceColumnNames the effective source column names
	 * @return a SchemaSource over the provided column names
	 */
	SchemaSource getSchemaSource(SourceHandler handler, List<String> sourceColumnNames);

	/**
	 * Creates a Copy implementation for row synchronization during Phase 2. The copy
	 * applies grid CRDT changes (insert/delete) directly via the
	 * {@code intendedChangePublisher} and reports surviving rows to the source
	 * handler.
	 *
	 * @param intendedChangePublisher publisher for recording grid CRDT changes
	 * @param finalSchema             the synchronized schema from Phase 1
	 * @param reader                  handler providing access to the copy's current
	 *                                rows
	 * @param handler                 the source handler, used for key extraction,
	 *                                baseline-based deletion detection, freezing, and
	 *                                surviving-row observation
	 * @return a RowCopy instance for synchronizing row data
	 */
	RowCopy getRowCopy(IntendedChangePublisher intendedChangePublisher, List<Column> finalSchema, CopyHandler reader,
	                   SourceHandler handler);

	/**
	 * Creates a Source implementation for row synchronization during Phase 2.
	 *
	 * @param sourceReader reader providing disk-based access to source rows (O(n)
	 *                     memory usage)
	 * @param handler      handler for applying changes to the source
	 * @return a RowSource instance for synchronizing row data
	 */
	RowSource getRowSource(RowSourceItemReader sourceReader, SourceHandler handler);

	/**
	 * Creates a Merge implementation for resolving conflicts during row
	 * synchronization in Phase 2. The merge writes user changes back to the source,
	 * applies grid CRDT changes via the {@code intendedChangePublisher}, and reports
	 * surviving rows to the source handler.
	 *
	 * @param logic                   the synchronization logic for recursive
	 *                                merging of cell-level changes
	 * @param intendedChangePublisher publisher for recording grid CRDT changes
	 * @param finalSchema             the synchronized schema from Phase 1
	 * @param reader                  handler for reading copy CRDT metadata
	 * @param handler                 the source handler for write-back and
	 *                                surviving-row observation
	 * @param preserveUserAttribution when true (PULL), user-changed cells are not
	 *                                rewritten in the grid, preserving attribution
	 * @return a RowMerge instance for resolving row conflicts
	 */
	RowMerge getRowMerge(SynchronizationLogic logic, IntendedChangePublisher intendedChangePublisher, List<Column> finalSchema, CopyHandler reader, SourceHandler handler,
	                     boolean preserveUserAttribution);

}
