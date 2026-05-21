package org.sagebionetworks.repo.manager.table;

import org.sagebionetworks.repo.model.entity.IdAndVersion;
import org.sagebionetworks.util.progress.ProgressCallback;

/**
 * Builds and maintains the queryable index for a {@code RecordSet} entity. Each
 * RecordSet version is built once from an immutable CSV file handle into its
 * own per-version index table (T{id}_{version}). Triggered by RECORDSET change
 * messages from {@link org.sagebionetworks.repo.service.metadata.RecordSetMetadataProvider}.
 */
public interface RecordSetIndexManager {

	/**
	 * Build (or rebuild) the index for the given RecordSet version. The schema is
	 * inferred from the CSV, persisted via
	 * {@link ColumnModelManager#bindColumnsToVersionOfObject(java.util.List, IdAndVersion)},
	 * and the rows are loaded into the per-version index table.
	 */
	void createOrUpdateRecordSetIndex(IdAndVersion idAndVersion, ProgressCallback progressCallback) throws Exception;

	/**
	 * Drop the per-version index for the given IdAndVersion. If the version is
	 * absent, this is treated as an entity-level delete: the version-less index
	 * (if any) is dropped and all column bindings for the object id are removed.
	 */
	void deleteRecordSetIndex(IdAndVersion idAndVersion);

}
