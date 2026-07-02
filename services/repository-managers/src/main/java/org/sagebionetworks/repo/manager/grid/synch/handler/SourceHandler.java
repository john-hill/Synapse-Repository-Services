package org.sagebionetworks.repo.manager.grid.synch.handler;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.sagebionetworks.repo.manager.grid.internal.replica.model.Column;
import org.sagebionetworks.repo.manager.grid.synch.io.RowSourceItem;
import org.sagebionetworks.repo.manager.grid.synch.io.RowSourceItemReader;
import org.sagebionetworks.repo.manager.grid.synch.row.RowCopyItem;
import org.sagebionetworks.repo.model.dao.asynch.AsyncJobProgressCallback;
import org.sagebionetworks.repo.model.grid.SyncType;
import org.sagebionetworks.repo.model.grid.patch.ConValue;

/**
 * Handler for reading from and writing to the source of truth during grid
 * synchronization. Provides both read access to the current source state and
 * write operations for applying changes from the copy (CRDT replica).
 *
 * <p>
 * This handler bridges the synchronization logic with the actual source
 * implementation, allowing the source to be any external source (EntityView,
 * Table, RecordSet, etc.) while maintaining a consistent interface for
 * synchronization.
 */
public interface SourceHandler extends AutoCloseable {

	/**
	 * Gets a disk-based reader for streaming all rows from the source. Used during
	 * Phase 2 (row synchronization) to compare source rows with copy rows without
	 * loading all data into memory (O(n) memory usage).
	 *
	 * @return a reader that streams rows from the source
	 * @throws IOException if reading from the source fails
	 */
	RowSourceItemReader getSourceRowReader() throws IOException;

	/**
	 * Gets the unique key used to identify a row in the source system. This key is
	 * used to match rows between copy and source during synchronization.
	 *
	 * @param rowView the row from the copy
	 * @return the source system's identifier for this row
	 */
	String getRowKey(RowCopyItem rowView);

	/**
	 * Adds a new row to the source. Called during synchronization when a row exists
	 * in the copy but not in the source, and the row was changed by the user
	 * (pushing the user's addition to the source).
	 *
	 * @param copy the row to add to the source
	 */
	void addNewRowToSource(RowSourceItem copy);

	/**
	 * Gets the current schema (column names) from the source. Used during Phase 1
	 * (schema synchronization) to compare source columns with copy columns.
	 *
	 * @return ordered list of column names defining the source schema
	 */
	List<String> getCurrentSourceSchema();

	/**
	 * Adds a new column to the source schema. Called during Phase 1 when a column
	 * exists in the copy but not in the source, and the column was added by the
	 * user (pushing the user's schema change to the source).
	 *
	 * @param name the name of the column to add
	 */
	void addColumnToSource(String name);

	/**
	 * Applies only the cells that changed in the copy to the source row. This
	 * prevents data loss when external changes occur in the source between reading
	 * and writing. By only updating cells that actually changed in the copy, any
	 * source cells that were modified after we read the row will be preserved.
	 *
	 * <p>
	 * Called during Phase 2 when merging cell-level conflicts between copy and
	 * source rows.
	 *
	 * @param rowId        the identifier of the row to update
	 * @param changedCells map of column names to new values (only cells that
	 *                     changed in the copy)
	 */
	void applyCellChangesFromCopyToSource(String rowId, Map<String, ConValue> changedCells);

	/**
	 * Deletes a column from the source schema. Called during Phase 1 when a column
	 * exists in the source but not in the copy, and the column was deleted by the
	 * user (pushing the user's schema change to the source).
	 *
	 * @param columnName the name of the column to delete
	 */
	void removeColumn(String columnName);

	/**
	 * Removes a row from the source. Called during synchronization when a row
	 * exists in the source but not in the copy, and the row was deleted by the user
	 * (pushing the user's deletion to the source).
	 *
	 * @param fetchRow the row to remove from the source
	 */
	void removeRow(RowSourceItem fetchRow);

	/**
	 * Returns whether rows can be added to or removed from this source. When false,
	 * rows that exist in the copy but not in the source will always be removed from
	 * the copy during synchronization, even if they were changed by the user.
	 *
	 * <p>
	 * Defaults to true. Override to return false for sources such as entity views,
	 * where row membership is determined by the view scope and cannot be modified by
	 * pushing rows from the copy.
	 *
	 * @return true if rows can be added to or removed from this source, false
	 *         otherwise
	 */
	default boolean canAddRemoveRows() {
		return true;
	}

	/**
	 * Returns whether columns can be added to or removed from this source. When
	 * false, columns that exist in the copy but not in the source will always be
	 * removed from the copy during synchronization, even if they were changed by
	 * the user.
	 *
	 * <p>
	 * Defaults to true. Override to return false for sources such as entity views,
	 * where the schema is determined by the view and cannot be modified by pushing
	 * columns from the copy.
	 *
	 * @return true if columns can be added to or removed from this source, false
	 *         otherwise
	 */
	default boolean canAddRemoveColumns() {
		return true;
	}

	/**
	 * Provide all error messages generated during the synchronization process to be
	 * forwarded to the caller.
	 *
	 * @return
	 */
	List<String> getErrorMessages();

	/**
	 * Returns the set of benefactor IDs collected from the source rows during
	 * initialization. For view-based sources this is the distinct set of benefactor
	 * IDs from the rows the action user can edit. Non-view sources return an empty
	 * set by default.
	 *
	 * @return the set of benefactor IDs, never null
	 */
	default Set<Long> getBenefactorIds() {
		return Collections.emptySet();
	}

	/**
	 * Returns whether the given row key existed in the synced baseline — the source
	 * revision the grid was last reconciled against. Used during Phase 2 to decide
	 * whether a row present in the source but absent from the grid was deleted by
	 * the user (key was in the baseline) versus newly added to the source (key was
	 * not in the baseline).
	 *
	 * <p>
	 * Defaults to false. Sources without a baseline concept (e.g. entity views)
	 * may inherit the default.
	 *
	 * @param key the row key to check
	 * @return true if the key was present in the synced baseline, false otherwise
	 */
	default boolean wasInSyncedBaseline(String key) {
		return false;
	}

	/**
	 * Returns the source revision this synchronization reconciled the grid to (the
	 * latest source revision read). After a successful pull, the orchestration
	 * records this as the session's baseline version
	 * ({@code sourceEntityVersionNumber}) for subsequent deletion detection.
	 *
	 * <p>
	 * Defaults to empty. Sources without a versioned revision concept (e.g. entity
	 * views) inherit the default, so no baseline version is recorded.
	 *
	 * @return the synchronized source revision, or empty if not applicable
	 */
	default Optional<Long> getSourceVersion() {
		return Optional.empty();
	}

	/**
	 * Returns the bound JSON schema $id the source's rows should be validated
	 * against, if any. After synchronization the orchestration records this as the
	 * session's {@code gridJsonSchema$Id} so that row validation runs against the
	 * current schema.
	 *
	 * <p>
	 * Defaults to empty. Sources without a bound schema concept inherit the
	 * default.
	 *
	 * @return the bound JSON schema $id, or empty if none
	 */
	default Optional<String> getSourceSchema$Id() {
		return Optional.empty();
	}

	/**
	 * Returns whether the given copy (grid) row is unmatchable during
	 * synchronization. If unmatchable, the row left entirely untouched in the copy,
	 * and is excluded from the keyed Phase 1 traversal.
	 *
	 * <p>
	 * For a RecordSet source this is true for rows with an incomplete
	 * {@code upsertKey}, which cannot be matched to a source row. An unmatchable row
	 * still survives in the grid and (for PULL_PUSH) is still written to the
	 * pushed CSV.
	 *
	 * <p>
	 * Defaults to false. Sources whose row identity is intrinsic (e.g. entity views,
	 * keyed by row id) inherit the default and all rows are considered matchable.
	 *
	 * @param row the copy row to test
	 * @return true if the row should be left untouched by synchronization
	 */
	default boolean isUnmatchableCopyRow(RowCopyItem row) {
		return false;
	}

	/**
	 * Returns whether the source row for the given key changed between the synced
	 * baseline revision and the latest revision. Used during Phase 2 to refine
	 * deletion detection: a row the user deleted from the grid is re-imported (not
	 * treated as a deletion) when the upstream source row has materially changed
	 * since the baseline, so the user can reconsider the new data.
	 *
	 * <p>
	 * Only meaningful for keys present in BOTH revisions. Defaults to false; sources
	 * without a baseline concept (e.g. entity views) should inherit the default.
	 *
	 * @param key the row key to check
	 * @return true if the source row changed since the synced baseline
	 */
	default boolean changedSinceBaseline(String key) {
		return false;
	}

	/**
	 * Validates the requested {@link SyncType} for this source. Each
	 * source type supports a different set of sync types; implementations must throw
	 * {@link IllegalArgumentException} for unsupported combinations.
	 *
	 * @param syncType the requested sync type (cannot be null)
	 * @throws IllegalArgumentException if the requested type is not supported by
	 *                                  this source
	 */
	void validateSyncType(SyncType syncType) throws IllegalArgumentException;

	/**
	 * Returns whether the given grid column should be excluded from Phase 1 schema
	 * matching — left in the grid, never dropped, and never pushed as a source
	 * schema change. Sources that preserve all existing grid columns (e.g.
	 * RecordSet) return true for a grid column that is absent from the current
	 * source schema; most sources let the engine drop such columns.
	 *
	 * <p>
	 * Defaults to false. Only meaningful for columns absent from the source schema
	 * (a column present in the source is matched and retained regardless).
	 *
	 * @param columnName the grid column name to test
	 * @return true if the grid column should be preserved untouched by schema sync
	 */
	default boolean isColumnExcludedFromMatching(String columnName) {
		return false;
	}

	/**
	 * Returns whether a source column absent from the grid was deleted by the user
	 * (rather than being a source-side addition to import). When true the column is
	 * not re-imported into the grid; when false it is added to the grid.
	 * <p>
	 * For a RecordSet source this is true when the grid is fully synced to the latest
	 * source version and the column is not a JSON Schema property; JSON Schema
	 * properties and columns from a newer-than-baseline source are always imported.
	 *
	 * <p>
	 * Defaults to false. Sources without a baseline concept (e.g. entity views)
	 * inherit the default and always import unmatched source columns.
	 *
	 * @param columnName the source column name, known to be absent from the grid
	 * @return true if the user deleted this column from the grid
	 */
	default boolean isColumnDeletedByUser(String columnName) {
		return false;
	}

	/**
	 * Prepares any push artifact this source builds during the merge. Called once,
	 * after Phase 1 schema synchronization and before the row merge, so the source
	 * can create a builder keyed to the final schema.
	 *
	 * <p>
	 * Defaults to a no-op. Only sources that push a new artifact (RecordSet
	 * PULL_PUSH) override it; all other sources/sync types inherit the no-op and
	 * push nothing.
	 *
	 * @param callback    the async-job progress callback (used for job id)
	 * @param finalSchema the synchronized schema produced by Phase 1
	 * @param syncType    the resolved sync type for this run
	 * @throws IOException if the push artifact cannot be opened
	 */
	default void beginPush(AsyncJobProgressCallback callback, List<Column> finalSchema, SyncType syncType)
			throws IOException {
		// no-op by default
	}

	/**
	 * Notification that a single grid row survived the merge with the given final
	 * cell values. The row merge feeds every surviving row here (merged, pulled in,
	 * retained, or user-added) and never feeds removed rows, so a source
	 * building a pushed artifact can capture the exact final grid contents.
	 *
	 * <p>
	 * Defaults to a no-op. Only sources that push an artifact (RecordSet PULL_PUSH)
	 * override it; all other sources/sync types inherit the no-op.
	 *
	 * @param finalCells the surviving row's final cell values, keyed by column name
	 */
	default void onSurvivingRow(Map<String, ConValue> finalCells) {
		// no-op by default
	}

	/**
	 * Flushes any push artifact accumulated during the merge to the source and
	 * returns the new source version number. Called after the row merge finishes.
	 *
	 * <p>
	 * Defaults to empty. Only sources that push an artifact (RecordSet PULL_PUSH)
	 * override it; all others inherit the empty default.
	 *
	 * @return the new source version number, or empty if no push was performed
	 * @throws Exception if the push fails
	 */
	default Optional<Long> completePush() throws Exception {
		return Optional.empty();
	}

}
