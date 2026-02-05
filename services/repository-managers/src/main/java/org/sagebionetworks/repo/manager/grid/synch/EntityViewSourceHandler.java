package org.sagebionetworks.repo.manager.grid.synch;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.json.JSONObject;
import org.sagebionetworks.repo.manager.grid.GridAuthorizationManager;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.SynapseRow;
import org.sagebionetworks.repo.manager.grid.row.translator.ColumnTypeToConType;
import org.sagebionetworks.repo.manager.grid.row.translator.Translator;
import org.sagebionetworks.repo.manager.schema.AnnotationsTranslator;
import org.sagebionetworks.repo.manager.schema.JsonSchemaManager;
import org.sagebionetworks.repo.manager.table.TableQueryManager;
import org.sagebionetworks.repo.model.ACCESS_TYPE;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.annotation.v2.AnnotationsValue;
import org.sagebionetworks.repo.model.dao.asynch.AsyncJobProgressCallback;
import org.sagebionetworks.repo.model.entity.IdAndVersion;
import org.sagebionetworks.repo.model.grid.GridSession;
import org.sagebionetworks.repo.model.grid.patch.ConType;
import org.sagebionetworks.repo.model.grid.patch.ConValue;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.Query;
import org.sagebionetworks.repo.model.table.Row;
import org.sagebionetworks.repo.model.table.TableFailedException;
import org.sagebionetworks.repo.model.table.TableUnavailableException;
import org.sagebionetworks.repo.web.NotFoundException;
import org.sagebionetworks.util.FileProvider;
import org.sagebionetworks.workers.util.semaphore.LockUnavilableException;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

@Component
@Scope("prototype")
public class EntityViewSourceHandler implements SourceHandler {

	private final AsyncJobProgressCallback callback;
	private final UserInfo user;
	private final GridSession session;
	private final TableQueryManager tableQueryManager;
	private final GridAuthorizationManager gridAuthorizationManager;
	private final FileProvider fileProvider;
	private final AnnotationWriter annotationWriter;
	private final JsonSchemaManager jsonSchemaManager;
	private final AnnotationsTranslator annotationsTranslator;
	private List<ColumnModel> schema;
	private List<DiskPointer> diskPointers;
	private File tempFile;
	private Map<String, Translator> translators;
	private Set<String> requiredColumnNames;

	public EntityViewSourceHandler(AsyncJobProgressCallback callback, UserInfo user, GridSession session,
			TableQueryManager tableQueryManager, GridAuthorizationManager gridAuthorizationManager,
			FileProvider fileProvider, AnnotationWriter annotationWriter, JsonSchemaManager jsonSchemaManager,
			AnnotationsTranslator annotationsTranslator) throws NotFoundException, LockUnavilableException,
			TableUnavailableException, TableFailedException, IOException {
		this.callback = callback;
		this.user = user;
		this.session = session;
		this.tableQueryManager = tableQueryManager;
		this.gridAuthorizationManager = gridAuthorizationManager;
		this.fileProvider = fileProvider;
		this.annotationWriter = annotationWriter;
		this.jsonSchemaManager = jsonSchemaManager;
		this.annotationsTranslator = annotationsTranslator;
		initialize();
	}

	void initialize() throws NotFoundException, LockUnavilableException, TableUnavailableException,
			TableFailedException, IOException {
		requiredColumnNames = session.getGridJsonSchema$Id() != null
				? new HashSet<>(jsonSchemaManager.getValidationSchema(session.getGridJsonSchema$Id()).getRequired())
				: Collections.emptySet();

		tempFile = fileProvider.createTempFile("Source" + session.getSourceEntityId(), ".bin");
		diskPointers = new ArrayList<>();
		try (RowWriter writer = new RowWriter(fileProvider.createFileOutputStream(tempFile))) {
			UserInfo sessionOwner = gridAuthorizationManager.getRowLevelFilterUserInfo(user, session.getSessionId());
			Query query = new Query().setSql("select * from " + session.getSourceEntityId());

			schema = new ArrayList<>();
			tableQueryManager.runQueryAsStream(callback, sessionOwner, query, t -> {
				schema.addAll(t.getMainQuery().getTranslator().getSchemaOfSelect());
				translators = schema.stream().collect(Collectors.toMap(ColumnModel::getName,
						cm -> ColumnTypeToConType.lookUpType(cm.getColumnType()).getTranslator()));
				return row -> diskPointers.add(writer.nextRow(createSynchRow(row)));
			}, ACCESS_TYPE.READ, ACCESS_TYPE.UPDATE);
		}
	}

	SynchRow createSynchRow(Row row) {
		String key = IdAndVersion.newBuilder().setId(row.getRowId()).toString();
		Map<String, ConValue> data = new HashMap<>();
		for (int i = 0; i < schema.size(); i++) {
			String columnName = schema.get(i).getName();
			ConValue conValue = translators.get(columnName).translateNullable(row.getValues().get(i),
					requiredColumnNames.contains(columnName));
			data.put(columnName, conValue);
		}
		return new SynchRow(data, key);
	}

	@Override
	public RowReader getSourceRowReader() throws IOException {
		return new RowReader(diskPointers, fileProvider.createRandomAccessFile(tempFile, "r"));
	}

	@Override
	public String getRowKey(CopyRow rowView) {
		SynapseRow synRow = rowView.getSynapseRow()
				.orElseThrow(() -> new IllegalArgumentException("Expected Synapse rows"));
		return IdAndVersion.newBuilder().setId(synRow.getRowId()).toString();
	}

	@Override
	public void addNewRowToSource(SynchRow copy) {
		throw new IllegalArgumentException("Cannot add a row to an entity view.");
	}

	@Override
	public List<String> getCurrentSourceSchema() {
		return schema.stream().map(ColumnModel::getName).collect(Collectors.toList());
	}

	@Override
	public void addColumnToSource(String name) {
		throw new IllegalArgumentException("Cannot add a column to an entity view.");
	}

	@Override
	public void applyCellChangesFromCopyToSource(String rowId, Map<String, ConValue> changes) {
		Map<String, AnnotationsValue> changedCells = new HashMap<>();
		for (Map.Entry<String, ConValue> e : changes.entrySet()) {
			ConValue cv = e.getValue();
			if (cv == null || ConType.UNDEFINED.equals(cv.getType()) || ConType.NULL.equals(cv.getType())) {
				changedCells.put(e.getKey(), null);
			} else {
				JSONObject json = new JSONObject();
				json.put(e.getKey(), cv.getValue());
				changedCells.put(e.getKey(), annotationsTranslator.getAnnotationValueFromJsonObject(e.getKey(), json));
			}
		}
		annotationWriter.updateChangedAnnotations(user, rowId, changedCells);
	}

	@Override
	public void close() {
		if (tempFile != null) {
			tempFile.delete();
		}
	}

	@Override
	public void deleteColumn(String columnName) {
		throw new IllegalArgumentException("Cannot delete a column of an entity view.");
	}
}
