package org.sagebionetworks.repo.manager.grid.internal.replica.view;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.grid.db.GridIndexManager;
import org.sagebionetworks.repo.manager.grid.PatchRowHandler;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.Column;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.GridHeader;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.RowData;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.RowMetadata;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.RowObject;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.RowValidation;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.RowView;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.SynapseRow;
import org.sagebionetworks.repo.manager.grid.internal.replica.view.query.QueryElement;
import org.sagebionetworks.repo.manager.grid.internal.replica.view.query.filter.CellValueFilterElement;
import org.sagebionetworks.repo.manager.grid.internal.replica.view.query.filter.CellValueOperatorElement;
import org.sagebionetworks.repo.manager.grid.internal.replica.view.query.filter.FilterElement;
import org.sagebionetworks.repo.manager.grid.internal.replica.view.query.filter.VectorIdFilterElement;
import org.sagebionetworks.repo.model.dbo.dao.table.TableModelTestUtils;
import org.sagebionetworks.repo.model.grid.GridUtils;
import org.sagebionetworks.repo.model.grid.ReplicaSelectionModel;
import org.sagebionetworks.repo.model.grid.patch.ConType;
import org.sagebionetworks.repo.model.grid.patch.ConValue;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;
import org.sagebionetworks.repo.model.grid.patch.Patch;
import org.sagebionetworks.repo.model.grid.patch.Timespan;
import org.sagebionetworks.repo.model.grid.patch.compact.PatchCompactSerializable;
import org.sagebionetworks.repo.model.grid.patch.operation.builder.InsertObjectBuilder;
import org.sagebionetworks.repo.model.grid.patch.operation.builder.NewConstantBuilder;
import org.sagebionetworks.repo.model.grid.patch.operation.builder.NewObjectBuilder;
import org.sagebionetworks.repo.model.grid.patch.operation.builder.Operations;
import org.sagebionetworks.repo.model.jdo.JDOSecondaryPropertyUtils;
import org.sagebionetworks.repo.model.schema.ValidationResults;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.repo.model.table.Row;
import org.sagebionetworks.schema.adapter.JSONObjectAdapterException;
import org.sagebionetworks.schema.adapter.org.json.EntityFactory;
import org.sagebionetworks.util.ClasspathUtil;
import org.semver4j.Semver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = { "classpath:test-context.xml" })
public class GridReplicaViewManagerImplAutowireTest {

	private final Long MAX_ROW_SIZE_BYTES = 1000L;

	@Autowired
	private GridIndexManager gridIndexManger;

	@Autowired
	private GridReplicaViewManager gridViewManager;

	private String sessionId;
	private Long replicaId;
	private List<ColumnModel> schema;
	private List<Row> rows;

	@BeforeEach
	public void before() {
		gridIndexManger.truncateAll();
		sessionId = GridUtils.gridSessionIdAsString(123L);
		replicaId = 111L;

		schema = List.of(new ColumnModel().setName("a").setColumnType(ColumnType.STRING),
				new ColumnModel().setName("b").setColumnType(ColumnType.INTEGER));

		rows = TableModelTestUtils.createRows(schema, 10);
	}

	@Test
	public void testGetGridHeader() throws IOException {

		// call under test
		assertEquals(Optional.empty(), gridViewManager.readHeader(sessionId, replicaId));

		writeRowsAsPatches(rows, sessionId, replicaId, schema, MAX_ROW_SIZE_BYTES);
		LogicalTimestamp expectedClock = new LogicalTimestamp().setReplicaId(replicaId).setSequenceNumber(85L);
		assertEquals(List.of(expectedClock), gridIndexManger.getClock(sessionId, replicaId));

		LogicalTimestamp columnOrderArrayId = new LogicalTimestamp().setReplicaId(replicaId).setSequenceNumber(4L);
		LogicalTimestamp columnNamesVecId = new LogicalTimestamp().setReplicaId(replicaId).setSequenceNumber(3L);

		GridHeader expected = new GridHeader().setSessionId(sessionId).setReplicaId(replicaId)
				.setDocumentVersion(new Semver("0.1.0"))
				.setOrderedColumns(List.of(new Column().setName("a").setVectorIndex(0),
						new Column().setName("b").setVectorIndex(1)))
				.setNodeId(new LogicalTimestamp().setReplicaId(replicaId).setSequenceNumber(1L))
				.setRowsId(new LogicalTimestamp().setReplicaId(replicaId).setSequenceNumber(5L))
				.setColumnOrderArrId(columnOrderArrayId).setColumnNamesVecId(columnNamesVecId)
				.setClockSequenceMaximum(expectedClock.getSequenceNumber());
		// call under test
		assertEquals(Optional.of(expected), gridViewManager.readHeader(sessionId, replicaId));

		// rename column b.
		Patch patch = new Patch()
				.setPatchId(LogicalTimestamp.newIncrement(gridIndexManger.getClock(sessionId, replicaId).get(0), 1));
		LogicalTimestamp b2Ref = patch
				.addNewOperation(Operations.newConstant().setValue(new ConValue(ConType.STRING, "b2")));
		patch.addNewOperation(Operations.insertVector().setVectorId(columnNamesVecId).setMap(Map.of(1, b2Ref)));
		gridIndexManger.applyPatch(sessionId, replicaId, patch);

		expected.getOrderedColumns().get(1).setName("b2");
		expected.setClockSequenceMaximum(patch.getPatchId().getSequenceNumber() + patch.getSpan());
		// call under test
		assertEquals(Optional.of(expected), gridViewManager.readHeader(sessionId, replicaId));

		// insert a new column at zero
		patch = new Patch()
				.setPatchId(LogicalTimestamp.newIncrement(gridIndexManger.getClock(sessionId, replicaId).get(0), 1));
		LogicalTimestamp cRef = patch
				.addNewOperation(Operations.newConstant().setValue(new ConValue(ConType.STRING, "c")));
		LogicalTimestamp twoRef = patch
				.addNewOperation(Operations.newConstant().setValue(new ConValue(ConType.LONG, 2L)));
		patch.addNewOperation(Operations.insertVector().setVectorId(columnNamesVecId).setMap(Map.of(2, cRef)));
		patch.addNewOperation(Operations.insertArray().setArrayId(columnOrderArrayId).setReferenceId(columnOrderArrayId)
				.setElementIds(List.of(twoRef)));
		gridIndexManger.applyPatch(sessionId, replicaId, patch);
		assertEquals(List.of(new LogicalTimestamp().setReplicaId(replicaId).setSequenceNumber(93L)),
				gridIndexManger.getClock(sessionId, replicaId));

		expected.setOrderedColumns(List.of(new Column().setName("c").setVectorIndex(2),
				new Column().setName("a").setVectorIndex(0), new Column().setName("b2").setVectorIndex(1)));
		expected.setClockSequenceMaximum(patch.getPatchId().getSequenceNumber() + patch.getSpan());
		// call under test
		assertEquals(Optional.of(expected), gridViewManager.readHeader(sessionId, replicaId));

		// add a selection model to the gird
		ReplicaSelectionModel selection = new ReplicaSelectionModel().setRowSelectAll(true).setColumnSelectAll(false);
		Long maxSeq = setSelection(expected.getNodeId(), selection);

		expected.setReplicaSelectionModel(selection);
		expected.setClockSequenceMaximum(maxSeq);
		// call under test
		assertEquals(Optional.of(expected), gridViewManager.readHeader(sessionId, replicaId));

	}

	/**
	 * Helper to set the selection model of the grid session.
	 * 
	 * @param rootObjectId
	 * @param selection
	 * @return
	 */
	public Long setSelection(LogicalTimestamp rootObjectId, ReplicaSelectionModel selection) {
		Patch patch = new Patch()
				.setPatchId(LogicalTimestamp.newIncrement(gridIndexManger.getClock(sessionId, replicaId).get(0), 1));
		LogicalTimestamp selectionConId = patch.addNewOperation(new NewConstantBuilder().setValue(
				new ConValue(ConType.JSON_OBJECT, JDOSecondaryPropertyUtils.createJSONObjectForEntity(selection))));
		LogicalTimestamp selectionObId = patch.addNewOperation(new NewObjectBuilder());
		patch.addNewOperation(new InsertObjectBuilder().setMap(Map.of(replicaId.toString(), selectionConId))
				.setObjectId(selectionObId));
		patch.addNewOperation(
				new InsertObjectBuilder().setObjectId(rootObjectId).setMap(Map.of("selection", selectionObId)));
		gridIndexManger.applyPatch(sessionId, replicaId, patch);
		return patch.getPatchId().getSequenceNumber() + patch.getSpan();
	}

	@Test
	public void testQuerySinglePage() throws IOException {
		writeRowsAsPatches(rows, sessionId, replicaId, schema, MAX_ROW_SIZE_BYTES);
		LogicalTimestamp expectedClock = new LogicalTimestamp().setReplicaId(replicaId).setSequenceNumber(85L);
		assertEquals(List.of(expectedClock), gridIndexManger.getClock(sessionId, replicaId));

		Long limit = 2L;
		Long offset = 3L;
		GridHeader header = gridViewManager.readHeader(sessionId, replicaId).get();
		// call under test
		List<RowView> page = gridViewManager.querySinglePage(header, limit, offset);
		List<RowView> expected = List.of(
				new RowView().setArrNodeId(new LogicalTimestamp().setReplicaId(replicaId).setSequenceNumber(42L))
						.setRowIndex(3L)
						.setRowObject(new RowObject()
								.setObjectId(new LogicalTimestamp().setReplicaId(replicaId).setSequenceNumber(36L))
								.setData(new RowData()
										.setVectorId(
												new LogicalTimestamp().setReplicaId(replicaId).setSequenceNumber(37L))
										.setCells(new JSONArray("[\"string3\",103003]")))
								.setMetadata(new RowMetadata().setRowValidation(new RowValidation())
										.setSynapseRow(new SynapseRow()))),
				new RowView().setArrNodeId(new LogicalTimestamp().setReplicaId(replicaId).setSequenceNumber(49L))
						.setRowIndex(4L)
						.setRowObject(new RowObject()
								.setObjectId(new LogicalTimestamp().setReplicaId(replicaId).setSequenceNumber(43L))
								.setData(new RowData()
										.setVectorId(
												new LogicalTimestamp().setReplicaId(replicaId).setSequenceNumber(44L))
										.setCells(new JSONArray("[\"string4\",103004]")))
								.setMetadata(new RowMetadata().setRowValidation(new RowValidation())
										.setSynapseRow(new SynapseRow()))));
		assertEquals(expected, page);

		// Add some metadata

		Patch newPatch = new Patch()
				.setPatchId(LogicalTimestamp.newIncrement(gridIndexManger.getClock(sessionId, replicaId).get(0), 1));

		LogicalTimestamp synapseRowRef = newPatch.addNewOperation(Operations.newConstant()
				.setValue(new ConValue(ConType.JSON_ARRAY, new JSONArray().put(111L).put(333L).put("etag88"))));

		// Since the row doesn't have any metadata yet we need to create the object
		LogicalTimestamp metadataRef = newPatch.addNewOperation(Operations.newObject());

		newPatch.addNewOperation(
				Operations.insertObject().setObjectId(metadataRef).setMap(Map.of("synapseRow", synapseRowRef)));

		// We also need to update the row object with the metadata now
		LogicalTimestamp rowObjectRef = expected.get(1).getRowObject().getObjectId();

		newPatch.addNewOperation(
				Operations.insertObject().setObjectId(rowObjectRef).setMap(Map.of("metadata", metadataRef)));

		gridIndexManger.applyPatch(sessionId, replicaId, newPatch);

		expected.get(1).getRowMetadata().setObjectId(metadataRef);
		expected.get(1).getSynapseRow().setConstantId(synapseRowRef).setRowId(111L).setVersionNumber(333L)
				.setEtag("etag88");

		// call under test
		page = gridViewManager.querySinglePage(header, limit, offset);
		assertEquals(expected, page);
	}

	@Test
	public void testQuerySinglePageWithDeletedRows() throws IOException {
		writeRowsAsPatches(rows, sessionId, replicaId, schema, MAX_ROW_SIZE_BYTES);
		LogicalTimestamp expectedClock = new LogicalTimestamp().setReplicaId(replicaId).setSequenceNumber(85L);
		assertEquals(List.of(expectedClock), gridIndexManger.getClock(sessionId, replicaId));

		Long limit = 100L;
		Long offset = 0L;

		GridHeader header = gridViewManager.readHeader(sessionId, replicaId).get();

		List<RowView> allRows = gridViewManager.querySinglePage(header, limit, offset);

		Patch patch = new Patch()
				.setPatchId(LogicalTimestamp.newIncrement(gridIndexManger.getClock(sessionId, replicaId).get(0), 1));

		patch.addNewOperation(Operations.delete().setNodeId(header.getRowsId()).setTimespans(List.of(
				// First three rows
				new Timespan(allRows.get(0).getArrNodeId(),
						allRows.get(2).getArrNodeId().getSequenceNumber()
								- allRows.get(0).getArrNodeId().getSequenceNumber() + 1),
				// 6th row
				new Timespan(allRows.get(5).getArrNodeId(), 1L),
				// Last two rows
				new Timespan(allRows.get(allRows.size() - 2).getArrNodeId(),
						allRows.get(allRows.size() - 1).getArrNodeId().getSequenceNumber()
								- allRows.get(allRows.size() - 2).getArrNodeId().getSequenceNumber() + 1))));

		gridIndexManger.applyPatch(sessionId, replicaId, patch);

		List<RowView> expected = new ArrayList<>();

		expected.addAll(allRows.subList(3, 5));
		expected.addAll(allRows.subList(6, allRows.size() - 2));

		// call under test
		List<RowView> page = gridViewManager.querySinglePage(header, limit, offset);

		assertEquals(expected, page);
	}

	@Test
	public void testQuerySinglePageWithFiltersOne() throws IOException {
		writeRowsAsPatches(rows, sessionId, replicaId, schema, MAX_ROW_SIZE_BYTES);
		LogicalTimestamp expectedClock = new LogicalTimestamp().setReplicaId(replicaId).setSequenceNumber(85L);
		assertEquals(List.of(expectedClock), gridIndexManger.getClock(sessionId, replicaId));

		Long limit = 100L;
		Long offset = 0L;
		GridHeader header = gridViewManager.readHeader(sessionId, replicaId).get();

		List<RowView> allRows = gridViewManager.querySinglePage(header, limit, offset);

		List<FilterElement> filters = List
				.of(new VectorIdFilterElement(List.of(allRows.get(1).getRowObject().getData().getVectorId(),
						allRows.get(4).getRowObject().getData().getVectorId())));

		// call under test
		List<RowView> page = gridViewManager.querySinglePage(header, filters, limit, offset);
		List<RowView> expected = List.of(allRows.get(1), allRows.get(4));
		assertEquals(expected, page);
	}

	@Test
	public void testQuerySinglePageWithFiltersMultiple() throws IOException {
		writeRowsAsPatches(rows, sessionId, replicaId, schema, MAX_ROW_SIZE_BYTES);
		LogicalTimestamp expectedClock = new LogicalTimestamp().setReplicaId(replicaId).setSequenceNumber(85L);
		assertEquals(List.of(expectedClock), gridIndexManger.getClock(sessionId, replicaId));

		Long limit = 100L;
		Long offset = 0L;
		GridHeader header = gridViewManager.readHeader(sessionId, replicaId).get();

		List<RowView> allRows = gridViewManager.querySinglePage(header, limit, offset);

		List<FilterElement> filters = List.of(
				// filter 4 rows by their vector id.
				new VectorIdFilterElement(List.of(allRows.get(1).getRowObject().getData().getVectorId(),
						allRows.get(4).getRowObject().getData().getVectorId(),
						allRows.get(5).getRowObject().getData().getVectorId(),
						allRows.get(9).getRowObject().getData().getVectorId())),
				// of those four rows only include rows with a cell value greater than 103004.
				new CellValueFilterElement().setColumnName(header.getOrderedColumns().get(1).getName())
						.setOperator(CellValueOperatorElement.GREATER_THAN).setValue(List.of(103004L)));

		// call under test
		List<RowView> page = gridViewManager.querySinglePage(header, filters, limit, offset);
		List<RowView> expected = List.of(allRows.get(5), allRows.get(9));
		assertEquals(expected, page);

	}

	@Test
	public void testQuerySinglePageWithRowValidation() throws IOException, JSONObjectAdapterException {
		writeRowsAsPatches(rows, sessionId, replicaId, schema, MAX_ROW_SIZE_BYTES);
		LogicalTimestamp expectedClock = new LogicalTimestamp().setReplicaId(replicaId).setSequenceNumber(85L);
		assertEquals(List.of(expectedClock), gridIndexManger.getClock(sessionId, replicaId));

		Long limit = 100L;
		Long offset = 0L;
		GridHeader header = gridViewManager.readHeader(sessionId, replicaId).get();

		List<RowView> allRows = gridViewManager.querySinglePage(header, limit, offset);

		RowView four = allRows.get(4);

		Patch patch = new Patch()
				.setPatchId(LogicalTimestamp.newIncrement(gridIndexManger.getClock(sessionId, replicaId).get(0), 1));

		ValidationResults validation = new ValidationResults().setIsValid(true);

		JSONObject validationJson = EntityFactory.createJSONObjectForEntity(validation);

		// Since the row doesn't have any metadata yet we need to create the object
		LogicalTimestamp metadataRef = patch.addNewOperation(Operations.newObject());

		// We also need to update the row object with the metadata now
		LogicalTimestamp rowObjectRef = four.getRowObject().getObjectId();

		patch.addNewOperation(
				Operations.insertObject().setObjectId(rowObjectRef).setMap(Map.of("metadata", metadataRef)));

		LogicalTimestamp conId = patch
				.addNewOperation(Operations.newConstant().setValue(new ConValue(ConType.JSON_OBJECT, validationJson)));

		patch.addNewOperation(
				Operations.insertObject().setObjectId(metadataRef).setMap(Map.of("rowValidation", conId)));

		gridIndexManger.applyPatch(sessionId, replicaId, patch);

		List<FilterElement> filters = List
				.of(new VectorIdFilterElement(List.of(four.getRowObject().getData().getVectorId())));

		// call under test
		List<RowView> page = gridViewManager.querySinglePage(header, filters, limit, offset);
		assertEquals(1, page.size());
		RowView fourUpdated = page.get(0);
		assertEquals(validation, fourUpdated.getRowValidationResults());
	}

	@Test
	public void testFilterWithEachColumnTypeAndEquals() throws IOException {
		schema = TableModelTestUtils.createOneOfEachType();

		rows = TableModelTestUtils.createRows(schema, 10);
		writeRowsAsPatches(rows, sessionId, replicaId, schema, MAX_ROW_SIZE_BYTES);

		Long limit = 100L;
		Long offset = 0L;
		GridHeader header = gridViewManager.readHeader(sessionId, replicaId).get();

		List<RowView> allRows = gridViewManager.querySinglePage(header,
				new QueryElement().setLimit(limit).setOffset(offset));

		RowView rowToFind = allRows.get(4);
		List<RowView> expected = List.of(rowToFind);

		for (int i = 0; i < schema.size(); i++) {
			ColumnModel cm = schema.get(i);
			Object value = rowToFind.getRowObject().getCells().get(i);
			// call under test
			List<RowView> filtered = gridViewManager.querySinglePage(header,
					new QueryElement()
							.setWhere(List.of(new CellValueFilterElement().setColumnName(cm.getName())
									.setOperator(CellValueOperatorElement.EQUALS).setValue(List.of(value))))
							.setLimit(limit).setOffset(offset));
			if (ColumnType.BOOLEAN.equals(cm.getColumnType()) || ColumnType.BOOLEAN_LIST.equals(cm.getColumnType())) {
				// half the the rows match this case.
				assertEquals(5, filtered.size());
			} else {
				assertEquals(expected, filtered, String.format("For: columnName: '%s', type: '%s',  value: %s",
						cm.getName(), cm.getColumnType().name(), value));
			}

		}
	}

	@Test
	public void testFilterWithEachColumnTypeAndIn() throws IOException {
		schema = TableModelTestUtils.createOneOfEachType();

		rows = TableModelTestUtils.createRows(schema, 10);
		writeRowsAsPatches(rows, sessionId, replicaId, schema, MAX_ROW_SIZE_BYTES);

		Long limit = 100L;
		Long offset = 0L;
		GridHeader header = gridViewManager.readHeader(sessionId, replicaId).get();

		List<RowView> allRows = gridViewManager.querySinglePage(header,
				new QueryElement().setLimit(limit).setOffset(offset));

		RowView rowToFindOne = allRows.get(4);
		RowView rowToFindTwo = allRows.get(7);
		List<RowView> expected = List.of(rowToFindOne, rowToFindTwo);

		for (int i = 0; i < schema.size(); i++) {
			ColumnModel cm = schema.get(i);
			Object v1 = rowToFindOne.getRowObject().getCells().get(i);
			Object v2 = rowToFindTwo.getRowObject().getCells().get(i);
			// call under test
			List<RowView> filtered = gridViewManager.querySinglePage(header,
					new QueryElement()
							.setWhere(List.of(new CellValueFilterElement().setColumnName(cm.getName())
									.setOperator(CellValueOperatorElement.IN).setValue(List.of(v1, v2))))
							.setLimit(limit).setOffset(offset));
			if (ColumnType.BOOLEAN.equals(cm.getColumnType()) || ColumnType.BOOLEAN_LIST.equals(cm.getColumnType())
					|| ColumnType.STRING_LIST.equals(cm.getColumnType())
					|| ColumnType.INTEGER_LIST.equals(cm.getColumnType())
					|| ColumnType.DATE_LIST.equals(cm.getColumnType())
					|| ColumnType.ENTITYID_LIST.equals(cm.getColumnType())
					|| ColumnType.USERID_LIST.equals(cm.getColumnType())
					|| ColumnType.JSON.equals(cm.getColumnType())) {
				// this does not currently work for these types
				assertEquals(0, filtered.size());
			} else {
				assertEquals(expected, filtered,
						String.format("For: columnName: '%s', type: '%s',  values: ['%s','%s']", cm.getName(),
								cm.getColumnType().name(), v1, v2));
			}
		}
	}

	@Test
	public void testFilterWithEachOperationAndNumeric() throws IOException {
		schema = List.of(new ColumnModel().setName("anInt").setColumnType(ColumnType.INTEGER));

		rows = List.of(new Row().setValues(Collections.emptyList()), new Row().setValues(List.of("1")),
				new Row().setValues(List.of("2")), new Row().setValues(Collections.emptyList()),
				new Row().setValues(List.of("3")), new Row().setValues(List.of("4")));
		writeRowsAsPatches(rows, sessionId, replicaId, schema, MAX_ROW_SIZE_BYTES);
		GridHeader header = gridViewManager.readHeader(sessionId, replicaId).get();
		List<RowView> allRows = gridViewManager.querySinglePage(header,
				new QueryElement().setLimit(100L).setOffset(0L));
		// call under test
		assertEquals(List.of(allRows.get(1)),
				gridViewManager.querySinglePage(header,
						new QueryElement()
								.setWhere(List.of(new CellValueFilterElement().setColumnName("anInt")
										.setOperator(CellValueOperatorElement.EQUALS).setValue(List.of(1L))))
								.setLimit(100L).setOffset(0L)));
		// call under test
		assertEquals(List.of(allRows.get(0),allRows.get(1),allRows.get(3)),
				gridViewManager.querySinglePage(header,
						new QueryElement()
								.setWhere(List.of(new CellValueFilterElement().setColumnName("anInt")
										.setOperator(CellValueOperatorElement.LESS_THAN).setValue(List.of(2L))))
								.setLimit(100L).setOffset(0L)));
		// call under test
		assertEquals(List.of(allRows.get(0),allRows.get(1),allRows.get(2),allRows.get(3)),
				gridViewManager.querySinglePage(header,
						new QueryElement()
								.setWhere(List.of(new CellValueFilterElement().setColumnName("anInt")
										.setOperator(CellValueOperatorElement.LESS_THAN_OR_EQUALS).setValue(List.of(2L))))
								.setLimit(100L).setOffset(0L)));
		// call under test
		assertEquals(List.of(allRows.get(4),allRows.get(5)),
				gridViewManager.querySinglePage(header,
						new QueryElement()
								.setWhere(List.of(new CellValueFilterElement().setColumnName("anInt")
										.setOperator(CellValueOperatorElement.GREATER_THAN).setValue(List.of(2L))))
								.setLimit(100L).setOffset(0L)));
		// call under test
		assertEquals(List.of(allRows.get(2),allRows.get(4),allRows.get(5)),
				gridViewManager.querySinglePage(header,
						new QueryElement()
								.setWhere(List.of(new CellValueFilterElement().setColumnName("anInt")
										.setOperator(CellValueOperatorElement.GREATER_THAN_OR_EQUALS).setValue(List.of(2L))))
								.setLimit(100L).setOffset(0L)));
		// call under test
		assertEquals(List.of(allRows.get(0),allRows.get(1), allRows.get(3),allRows.get(4),allRows.get(5)),
				gridViewManager.querySinglePage(header,
						new QueryElement()
								.setWhere(List.of(new CellValueFilterElement().setColumnName("anInt")
										.setOperator(CellValueOperatorElement.NOT_EQUALS).setValue(List.of(2L))))
								.setLimit(100L).setOffset(0L)));
		// call under test
		assertEquals(List.of(allRows.get(0),allRows.get(3)),
				gridViewManager.querySinglePage(header,
						new QueryElement()
								.setWhere(List.of(new CellValueFilterElement().setColumnName("anInt")
										.setOperator(CellValueOperatorElement.IS_NULL)))
								.setLimit(100L).setOffset(0L)));
		// call under test
		assertEquals(List.of(allRows.get(1),allRows.get(2),allRows.get(4),allRows.get(5)),
				gridViewManager.querySinglePage(header,
						new QueryElement()
								.setWhere(List.of(new CellValueFilterElement().setColumnName("anInt")
										.setOperator(CellValueOperatorElement.IS_NOT_NULL)))
								.setLimit(100L).setOffset(0L)));
		// call under test
		assertEquals(List.of(allRows.get(1),allRows.get(5)),
				gridViewManager.querySinglePage(header,
						new QueryElement()
								.setWhere(List.of(new CellValueFilterElement().setColumnName("anInt")
										.setOperator(CellValueOperatorElement.IN).setValue(1L,4L)))
								.setLimit(100L).setOffset(0L)));
		// call under test
		assertEquals(List.of(allRows.get(0),allRows.get(2),allRows.get(3),allRows.get(4)),
				gridViewManager.querySinglePage(header,
						new QueryElement()
								.setWhere(List.of(new CellValueFilterElement().setColumnName("anInt")
										.setOperator(CellValueOperatorElement.NOT_IN).setValue(1L,4L)))
								.setLimit(100L).setOffset(0L)));
	}
	
	@Test
	public void testFilterWithEachOperationAndString() throws IOException {
		schema = List.of(new ColumnModel().setName("aString").setColumnType(ColumnType.STRING).setMaximumSize(100L));

		rows = List.of(new Row().setValues(Collections.emptyList()), new Row().setValues(List.of("a")),
				new Row().setValues(List.of("b")), new Row().setValues(Collections.emptyList()),
				new Row().setValues(List.of("c")), new Row().setValues(List.of("d")));
		writeRowsAsPatches(rows, sessionId, replicaId, schema, MAX_ROW_SIZE_BYTES);
		GridHeader header = gridViewManager.readHeader(sessionId, replicaId).get();
		List<RowView> allRows = gridViewManager.querySinglePage(header,
				new QueryElement().setLimit(100L).setOffset(0L));
		// call under test
		assertEquals(List.of(allRows.get(1)),
				gridViewManager.querySinglePage(header,
						new QueryElement()
								.setWhere(List.of(new CellValueFilterElement().setColumnName("aString")
										.setOperator(CellValueOperatorElement.EQUALS).setValue(List.of("a"))))
								.setLimit(100L).setOffset(0L)));
		// call under test
		assertEquals(List.of(allRows.get(1)),
				gridViewManager.querySinglePage(header,
						new QueryElement()
								.setWhere(List.of(new CellValueFilterElement().setColumnName("aString")
										.setOperator(CellValueOperatorElement.LESS_THAN).setValue(List.of("b"))))
								.setLimit(100L).setOffset(0L)));
		// call under test
		assertEquals(List.of(allRows.get(1),allRows.get(2)),
				gridViewManager.querySinglePage(header,
						new QueryElement()
								.setWhere(List.of(new CellValueFilterElement().setColumnName("aString")
										.setOperator(CellValueOperatorElement.LESS_THAN_OR_EQUALS).setValue(List.of("b"))))
								.setLimit(100L).setOffset(0L)));
		// call under test
		assertEquals(List.of(allRows.get(0), allRows.get(3), allRows.get(4),allRows.get(5)),
				gridViewManager.querySinglePage(header,
						new QueryElement()
								.setWhere(List.of(new CellValueFilterElement().setColumnName("aString")
										.setOperator(CellValueOperatorElement.GREATER_THAN).setValue(List.of("b"))))
								.setLimit(100L).setOffset(0L)));
		// call under test
		assertEquals(List.of(allRows.get(0), allRows.get(2), allRows.get(3), allRows.get(4),allRows.get(5)),
				gridViewManager.querySinglePage(header,
						new QueryElement()
								.setWhere(List.of(new CellValueFilterElement().setColumnName("aString")
										.setOperator(CellValueOperatorElement.GREATER_THAN_OR_EQUALS).setValue(List.of("b"))))
								.setLimit(100L).setOffset(0L)));
		// call under test
		assertEquals(List.of(allRows.get(0),allRows.get(1), allRows.get(3),allRows.get(4),allRows.get(5)),
				gridViewManager.querySinglePage(header,
						new QueryElement()
								.setWhere(List.of(new CellValueFilterElement().setColumnName("aString")
										.setOperator(CellValueOperatorElement.NOT_EQUALS).setValue(List.of("b"))))
								.setLimit(100L).setOffset(0L)));
		// call under test
		assertEquals(List.of(allRows.get(0),allRows.get(3)),
				gridViewManager.querySinglePage(header,
						new QueryElement()
								.setWhere(List.of(new CellValueFilterElement().setColumnName("aString")
										.setOperator(CellValueOperatorElement.IS_NULL)))
								.setLimit(100L).setOffset(0L)));
		// call under test
		assertEquals(List.of(allRows.get(1),allRows.get(2),allRows.get(4),allRows.get(5)),
				gridViewManager.querySinglePage(header,
						new QueryElement()
								.setWhere(List.of(new CellValueFilterElement().setColumnName("aString")
										.setOperator(CellValueOperatorElement.IS_NOT_NULL)))
								.setLimit(100L).setOffset(0L)));
		// call under test
		assertEquals(List.of(allRows.get(1),allRows.get(4)),
				gridViewManager.querySinglePage(header,
						new QueryElement()
								.setWhere(List.of(new CellValueFilterElement().setColumnName("aString")
										.setOperator(CellValueOperatorElement.IN).setValue("a","c")))
								.setLimit(100L).setOffset(0L)));
		// call under test
		assertEquals(List.of(allRows.get(0),allRows.get(2),allRows.get(3),allRows.get(5)),
				gridViewManager.querySinglePage(header,
						new QueryElement()
								.setWhere(List.of(new CellValueFilterElement().setColumnName("aString")
										.setOperator(CellValueOperatorElement.NOT_IN).setValue("a","c")))
								.setLimit(100L).setOffset(0L)));
	}
	
	@Test
	public void testFilterWithEachLikeOperation() throws IOException {
		schema = List.of(new ColumnModel().setName("aString").setColumnType(ColumnType.STRING).setMaximumSize(100L));

		rows = List.of(new Row().setValues(Collections.emptyList()), new Row().setValues(List.of("this is one")),
				new Row().setValues(List.of("this is two")), new Row().setValues(Collections.emptyList()),
				new Row().setValues(List.of("three this is")));
		writeRowsAsPatches(rows, sessionId, replicaId, schema, MAX_ROW_SIZE_BYTES);
		GridHeader header = gridViewManager.readHeader(sessionId, replicaId).get();
		List<RowView> allRows = gridViewManager.querySinglePage(header,
				new QueryElement().setLimit(100L).setOffset(0L));
		// call under test
		assertEquals(List.of(allRows.get(1),allRows.get(2)),
				gridViewManager.querySinglePage(header,
						new QueryElement()
								.setWhere(List.of(new CellValueFilterElement().setColumnName("aString")
										.setOperator(CellValueOperatorElement.LIKE).setValue("this is%")))
								.setLimit(100L).setOffset(0L)));
		// call under test
		assertEquals(List.of(allRows.get(1),allRows.get(2),allRows.get(4)),
				gridViewManager.querySinglePage(header,
						new QueryElement()
								.setWhere(List.of(new CellValueFilterElement().setColumnName("aString")
										.setOperator(CellValueOperatorElement.LIKE).setValue("%this%")))
								.setLimit(100L).setOffset(0L)));
		// call under test
		assertEquals(List.of(allRows.get(0),allRows.get(3),allRows.get(4)),
				gridViewManager.querySinglePage(header,
						new QueryElement()
								.setWhere(List.of(new CellValueFilterElement().setColumnName("aString")
										.setOperator(CellValueOperatorElement.NOT_LIKE).setValue("this is%")))
								.setLimit(100L).setOffset(0L)));

	}

	/**
	 * This is a test for
	 * <a href=" https://sagebionetworks.jira.com/browse/PLFM-9220">PLFM-9220</a>
	 */
	@Test
	public void testMissingRows() {
		replicaId = 66534L;
		List.of("patches/b6e3e983-e918-48fa-9460-35eec7a6e953.json",
				"patches/d2cc8633-db04-45e1-8ef8-ce6cee4daa59.json",
				"patches/1579ca83-f9bb-45bb-88e9-7cc84aebfa28.json",
				"patches/a41969be-d0a0-4f83-b6db-4ab5eb0ab947.json").stream().map(f -> {
					try {
						return PatchCompactSerializable.deserialize(new JSONArray(ClasspathUtil.loadFromClasspath(f)));
					} catch (JSONException | IOException e) {
						throw new RuntimeException(e);
					}
				}).forEach(p -> {
					gridIndexManger.applyPatch(sessionId, replicaId, p);
				});

		GridHeader header = gridViewManager.readHeader(sessionId, replicaId).get();
		List<RowView> page = gridViewManager.querySinglePage(header, List.of(), 100L, 0L);
		assertEquals(7, page.size());
	}

	/**
	 * Helper function to apply the provided rows as a set of patches to the replica
	 * index.
	 * 
	 * @param rows
	 * @param sessionId
	 * @param replicaId
	 * @param schema
	 * @param maxRowSizeBytes
	 * @throws IOException
	 */
	void writeRowsAsPatches(List<Row> rows, String sessionId, Long replicaId, List<ColumnModel> schema,
			Long maxRowSizeBytes) throws IOException {
		try (PatchRowHandler patchRowHandler = new PatchRowHandler((s, pid, body) -> {
			Patch patch = PatchCompactSerializable.deserialize(new JSONArray(body));
			gridIndexManger.applyPatch(sessionId, pid.getReplicaId(), patch);
			return true;
		}, sessionId, replicaId, schema, MAX_ROW_SIZE_BYTES)) {
			rows.stream().forEach(r -> {
				patchRowHandler.nextRow(r);
			});
		}
	}

}
