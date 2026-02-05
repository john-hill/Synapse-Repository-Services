package org.sagebionetworks.repo.manager.grid.synch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.manager.grid.internal.replica.change.AddColumn;
import org.sagebionetworks.repo.manager.grid.internal.replica.change.DeleteColumn;
import org.sagebionetworks.repo.manager.grid.internal.replica.change.DeleteRowChange;
import org.sagebionetworks.repo.manager.grid.internal.replica.change.InsertRowChange;
import org.sagebionetworks.repo.manager.grid.internal.replica.change.IntendedChangePublisher;
import org.sagebionetworks.repo.manager.grid.internal.replica.change.PatchBuilderPublisher;
import org.sagebionetworks.repo.manager.grid.internal.replica.change.UpdateRowChange;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.Column;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.GridHeader;
import org.sagebionetworks.repo.model.EntityType;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dao.asynch.AsyncJobProgressCallback;
import org.sagebionetworks.repo.model.dbo.grid.GridSource;
import org.sagebionetworks.repo.model.grid.GridSession;
import org.sagebionetworks.repo.model.grid.patch.ConType;
import org.sagebionetworks.repo.model.grid.patch.ConValue;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;
import org.sagebionetworks.repo.web.NotFoundException;

import com.google.common.base.Functions;

@ExtendWith(MockitoExtension.class)
public class GridSynchronizationManagerImplTest {

	@Mock
	private PatchBuilderPublisher mockPatchBuilderPublisher;
	@Mock
	private SourceHandlerProvdier mockSourceHandlerProvdier;
	@Mock
	private CopyReaderProvider mockCopyReaderProvider;

	@Spy
	@InjectMocks
	private GridSynchronizationManagerImpl manager;

	@Mock
	private CopyReader mockCopyReader;
	@Mock
	private SourceHandler mockSourceHandler;
	@Mock
	private RowReader mockRowReader;
	@Mock
	private AsyncJobProgressCallback mockCallback;
	@Mock
	private UserInfo mockUser;
	@Mock
	private IntendedChangePublisher mockIntendedChangePublisher;
	@Mock
	private GridHeader mockHeader;

	private GridSession session;
	private GridSource gridSource;
	private List<Column> finalSchema;
	private Map<String, Column> columnNameMap;
	private LogicalTimestamp columnOrderArrId;
	private LogicalTimestamp columnNamesVectorId;

	private long internalReplicaId = 0xfff;
	private long userReplciaId = 0xabcL;

	@BeforeEach
	public void before() {
		session = new GridSession().setSessionId("123");
		gridSource = new GridSource(444L, EntityType.entityview);
		setFinalSchema(List.of(new Column().setName("one").setVectorIndex(2)
				.setColumnOrderNodeId(new LogicalTimestamp().setReplicaId(1L).setSequenceNumber(2L))));
		columnOrderArrId = new LogicalTimestamp().setReplicaId(internalReplicaId).setSequenceNumber(0xabcL);
		columnNamesVectorId = new LogicalTimestamp().setReplicaId(internalReplicaId).setSequenceNumber(0xaaaL);
	}

	void setFinalSchema(List<Column> schema) {
		finalSchema = schema;
		columnNameMap = finalSchema.stream().collect(Collectors.toMap(Column::getName, Functions.identity()));
	}

	@Test
	public void testSynchronizeCopyWithSource() throws Exception {

		when(mockCopyReaderProvider.createCopyReader(session)).thenReturn(mockCopyReader);
		when(mockSourceHandlerProvdier.createNewProvider(mockCallback, mockUser, session, gridSource))
				.thenReturn(mockSourceHandler);
		when(mockSourceHandler.getSourceRowReader()).thenReturn(mockRowReader);
		when(mockCopyReader.getGridSource()).thenReturn(gridSource);

		doReturn(mockIntendedChangePublisher).when(manager).newIntendedChangePublisher(mockCopyReader);
		doReturn(finalSchema).when(manager).synchronizeSchema(mockSourceHandler, mockCopyReader,
				mockIntendedChangePublisher);

		doNothing().when(manager).synchronizeExistingRows(mockCopyReader, mockSourceHandler,
				mockIntendedChangePublisher, columnNameMap, mockRowReader);
		doNothing().when(manager).addRemainingSourceRows(mockCopyReader, mockRowReader, mockIntendedChangePublisher,
				columnNameMap);

		// call under test
		manager.synchronizeCopyWithSource(mockCallback, mockUser, session);

		verify(mockCopyReader).close();
		verify(mockSourceHandler).close();
		verify(mockRowReader).close();
		verify(mockIntendedChangePublisher).close();

	}

	@Test
	public void testSynchronizeSchemaWithNoChange() {
		List<Column> copySchema = List.of(new Column().setName("one").setVectorIndex(1)
				.setColumnOrderNodeId(new LogicalTimestamp().setReplicaId(1L).setSequenceNumber(2L)));
		when(mockSourceHandler.getCurrentSourceSchema()).thenReturn(List.of("one"));
		when(mockCopyReader.getHeader()).thenReturn(mockHeader);
		when(mockHeader.getOrderedColumns()).thenReturn(copySchema);

		// call under test
		List<Column> finalSchema = manager.synchronizeSchema(mockSourceHandler, mockCopyReader,
				mockIntendedChangePublisher);

		assertEquals(copySchema, finalSchema);

		verifyNoMoreInteractions(mockIntendedChangePublisher, mockCopyReader, mockSourceHandler, mockHeader);
	}

	@Test
	public void testSynchronizeSchemaWithColumnDeletedFromSource() {
		List<Column> copySchema = List.of(
				new Column().setName("one").setVectorIndex(0).setColumnOrderNodeId(
						new LogicalTimestamp().setReplicaId(internalReplicaId).setSequenceNumber(2L)),
				new Column().setName("two").setVectorIndex(1).setColumnOrderNodeId(
						new LogicalTimestamp().setReplicaId(internalReplicaId).setSequenceNumber(3L)));

		when(mockCopyReader.getInternalReplicaId()).thenReturn(internalReplicaId);

		when(mockSourceHandler.getCurrentSourceSchema()).thenReturn(List.of("one"));
		when(mockCopyReader.getHeader()).thenReturn(mockHeader);
		when(mockHeader.getOrderedColumns()).thenReturn(copySchema);
		when(mockHeader.getColumnOrderArrId()).thenReturn(columnOrderArrId);

		// call under test
		List<Column> finalSchema = manager.synchronizeSchema(mockSourceHandler, mockCopyReader,
				mockIntendedChangePublisher);

		assertEquals(copySchema.subList(0, 1), finalSchema);
		// the column should be deleted from the copy
		verify(mockIntendedChangePublisher)
				.publish(new DeleteColumn(columnOrderArrId, copySchema.get(1).getColumnOrderNodeIdAsLogical()));
		verifyNoMoreInteractions(mockIntendedChangePublisher, mockCopyReader, mockSourceHandler, mockHeader);
	}

	/*
	 * Note for this case the column will get added back from the source.
	 */
	@Test
	public void testSynchronizeSchemaWithColumnDeletedFromCopy() {
		List<Column> copySchema = List.of(new Column().setName("two").setVectorIndex(1)
				.setColumnOrderNodeId(new LogicalTimestamp().setReplicaId(internalReplicaId).setSequenceNumber(3L)));

		when(mockSourceHandler.getCurrentSourceSchema()).thenReturn(List.of("one","two"));
		when(mockCopyReader.getHeader()).thenReturn(mockHeader);
		when(mockHeader.getOrderedColumns()).thenReturn(copySchema);
		when(mockHeader.getColumnOrderArrId()).thenReturn(columnOrderArrId);
		when(mockHeader.getColumnNamesVecId()).thenReturn(columnNamesVectorId);

		// call under test
		List<Column> finalSchema = manager.synchronizeSchema(mockSourceHandler, mockCopyReader,
				mockIntendedChangePublisher);

		assertEquals(List.of(copySchema.get(0), new Column().setName("one").setVectorIndex(2)), finalSchema);
		verify(mockIntendedChangePublisher).publish(new AddColumn(columnOrderArrId, new ConValue(ConType.LONG, 2L)));
		verify(mockIntendedChangePublisher).publish(new UpdateRowChange(columnNamesVectorId,
				List.of(new ConValue(ConType.STRING, "two"), new ConValue(ConType.STRING, "one")),
				new Integer[] { 1, 2 }));
		verifyNoMoreInteractions(mockIntendedChangePublisher, mockCopyReader, mockSourceHandler, mockHeader);
	}

	@Test
	public void testSynchronizeSchemaWithColumnAddedToCopy() {
		// column two's replicaId indicates it was added by a user's replica.
		List<Column> copySchema = List.of(
				new Column().setName("one").setVectorIndex(1).setColumnOrderNodeId(
						new LogicalTimestamp().setReplicaId(internalReplicaId).setSequenceNumber(2L)),
				new Column().setName("two").setVectorIndex(1)
						.setColumnOrderNodeId(new LogicalTimestamp().setReplicaId(444L).setSequenceNumber(3L)));

		when(mockCopyReader.getInternalReplicaId()).thenReturn(internalReplicaId);

		when(mockSourceHandler.getCurrentSourceSchema()).thenReturn(List.of("one"));
		when(mockCopyReader.getHeader()).thenReturn(mockHeader);
		when(mockHeader.getOrderedColumns()).thenReturn(copySchema);

		// call under test
		List<Column> finalSchema = manager.synchronizeSchema(mockSourceHandler, mockCopyReader,
				mockIntendedChangePublisher);

		assertEquals(copySchema, finalSchema);
		// the column should be added to the source.
		verify(mockSourceHandler).addColumnToSource("two");
		verifyNoMoreInteractions(mockIntendedChangePublisher, mockCopyReader, mockSourceHandler, mockHeader);
	}

	@Test
	public void testSynchronizeSchemaWithColumnAddedToSource() {
		List<Column> copySchema = List.of(new Column().setName("one").setVectorIndex(4)
				.setColumnOrderNodeId(new LogicalTimestamp().setReplicaId(internalReplicaId).setSequenceNumber(2L)));

		when(mockSourceHandler.getCurrentSourceSchema()).thenReturn(List.of("one","two","three"));
		when(mockCopyReader.getHeader()).thenReturn(mockHeader);
		when(mockHeader.getOrderedColumns()).thenReturn(copySchema);
		when(mockHeader.getColumnOrderArrId()).thenReturn(columnOrderArrId);
		when(mockHeader.getColumnNamesVecId()).thenReturn(columnNamesVectorId);

		// call under test
		List<Column> finalSchema = manager.synchronizeSchema(mockSourceHandler, mockCopyReader,
				mockIntendedChangePublisher);

		assertEquals(List.of(copySchema.get(0), new Column().setName("two").setVectorIndex(5),
				new Column().setName("three").setVectorIndex(6)), finalSchema);
		verify(mockIntendedChangePublisher).publish(new AddColumn(columnOrderArrId, new ConValue(ConType.LONG, 5L)));
		verify(mockIntendedChangePublisher).publish(new AddColumn(columnOrderArrId, new ConValue(ConType.LONG, 6L)));
		verify(mockIntendedChangePublisher)
				.publish(new UpdateRowChange(
						columnNamesVectorId, List.of(new ConValue(ConType.STRING, "one"),
								new ConValue(ConType.STRING, "two"), new ConValue(ConType.STRING, "three")),
						new Integer[] { 4, 5, 6 }));
		verifyNoMoreInteractions(mockIntendedChangePublisher, mockCopyReader, mockSourceHandler, mockHeader);
	}

	@Test
	public void testSynchronizeSchemaWithColumnAddedToSourceAndCopySchemaEmpty() {
		List<Column> copySchema = Collections.emptyList();

		when(mockSourceHandler.getCurrentSourceSchema()).thenReturn(List.of("one"));
		when(mockCopyReader.getHeader()).thenReturn(mockHeader);
		when(mockHeader.getOrderedColumns()).thenReturn(copySchema);
		when(mockHeader.getColumnOrderArrId()).thenReturn(columnOrderArrId);
		when(mockHeader.getColumnNamesVecId()).thenReturn(columnNamesVectorId);

		// call under test
		List<Column> finalSchema = manager.synchronizeSchema(mockSourceHandler, mockCopyReader,
				mockIntendedChangePublisher);

		assertEquals(List.of(new Column().setName("one").setVectorIndex(0)), finalSchema);
		verify(mockIntendedChangePublisher).publish(new AddColumn(columnOrderArrId, new ConValue(ConType.LONG, 0L)));
		verify(mockIntendedChangePublisher).publish(new UpdateRowChange(columnNamesVectorId,
				List.of(new ConValue(ConType.STRING, "one")), new Integer[] { 0 }));
		verifyNoMoreInteractions(mockIntendedChangePublisher, mockCopyReader, mockSourceHandler, mockHeader);
	}

	@Test
	public void testSynchronizeExistingRowsWithNoChnage() throws IOException {

		List<CopyRow> copyRows = List.of(new CopyRowImpl()
				.setCells(List.of(new CopyCell().setName("one").setValue(new ConValue(ConType.STRING, "a"))
						.setWasChangedByUser(false)))
				.setRgaNodeId(new LogicalTimestamp().setReplicaId(internalReplicaId).setSequenceNumber(1L))
				.setVectorNodeId(new LogicalTimestamp().setReplicaId(internalReplicaId).setSequenceNumber(2L)));

		when(mockCopyReader.getRows()).thenReturn(copyRows.iterator());
		when(mockSourceHandler.getRowKey(copyRows.get(0))).thenReturn("syn111");

		SynchRow row = new SynchRow(Map.of("one", new ConValue(ConType.STRING, "a")), "syn111");
		try (RowReader rowReader = setupSourceRows(List.of(row))) {
			// call under test
			manager.synchronizeExistingRows(mockCopyReader, mockSourceHandler, mockIntendedChangePublisher,
					columnNameMap, rowReader);
		}
		verifyNoMoreInteractions(mockIntendedChangePublisher, mockCopyReader, mockSourceHandler, mockHeader);
	}

	@Test
	public void testSynchronizeExistingRowsWithSourceCellUpdate() throws IOException {

		setFinalSchema(List.of(new Column().setName("one").setVectorIndex(1)));

		List<CopyRow> copyRows = List.of(new CopyRowImpl()
				.setCells(List.of(new CopyCell().setName("one").setValue(new ConValue(ConType.STRING, "a"))
						.setWasChangedByUser(false)))
				.setRgaNodeId(new LogicalTimestamp().setReplicaId(internalReplicaId).setSequenceNumber(1L))
				.setVectorNodeId(new LogicalTimestamp().setReplicaId(internalReplicaId).setSequenceNumber(2L)));

		when(mockCopyReader.getRows()).thenReturn(copyRows.iterator());
		when(mockSourceHandler.getRowKey(copyRows.get(0))).thenReturn("syn111");
		when(mockCopyReader.getInternalReplicaId()).thenReturn(internalReplicaId);

		SynchRow row = new SynchRow(Map.of("one", new ConValue(ConType.STRING, "b")), "syn111");
		try (RowReader rowReader = setupSourceRows(List.of(row))) {
			// call under test
			manager.synchronizeExistingRows(mockCopyReader, mockSourceHandler, mockIntendedChangePublisher,
					columnNameMap, rowReader);
		}
		// change from the source is pushed to the grid.
		verify(mockIntendedChangePublisher).publish(new UpdateRowChange(copyRows.get(0).getVectorNodeId(),
				List.of(new ConValue(ConType.STRING, "b")), new Integer[] { 1 }));
		verifyNoMoreInteractions(mockIntendedChangePublisher, mockCopyReader, mockSourceHandler, mockHeader);
	}

	@Test
	public void testSynchronizeExistingRowsWithSourceCellMissing() throws IOException {

		setFinalSchema(
				List.of(new Column().setName("one").setVectorIndex(1), new Column().setName("two").setVectorIndex(0)));

		List<CopyRow> copyRows = List.of(new CopyRowImpl()
				.setCells(List.of(
						new CopyCell().setName("one").setValue(new ConValue(ConType.STRING, "a"))
								.setWasChangedByUser(false),
						new CopyCell().setName("two").setValue(new ConValue(ConType.LONG, 88L))
								.setWasChangedByUser(false)))
				.setRgaNodeId(new LogicalTimestamp().setReplicaId(internalReplicaId).setSequenceNumber(1L))
				.setVectorNodeId(new LogicalTimestamp().setReplicaId(internalReplicaId).setSequenceNumber(2L)));

		when(mockCopyReader.getRows()).thenReturn(copyRows.iterator());
		when(mockSourceHandler.getRowKey(copyRows.get(0))).thenReturn("syn111");
		when(mockCopyReader.getInternalReplicaId()).thenReturn(internalReplicaId);

		SynchRow row = new SynchRow(Map.of("one", new ConValue(ConType.STRING, "a")), "syn111");
		try (RowReader rowReader = setupSourceRows(List.of(row))) {
			// call under test
			manager.synchronizeExistingRows(mockCopyReader, mockSourceHandler, mockIntendedChangePublisher,
					columnNameMap, rowReader);
		}

		// change is pushed to the source.
		verify(mockSourceHandler).applyCellChangesFromCopyToSource("syn111",
				Map.of("two", new ConValue(ConType.LONG, 88L)));
//		
//		// change from the source is pushed to the grid.
//		verify(mockIntendedChangePublisher).publish(new UpdateRowChange(copyRows.get(0).getVectorNodeId(),
//				List.of(new ConValue(ConType.STRING, "b")), new Integer[] { 1 }));
		verifyNoMoreInteractions(mockIntendedChangePublisher, mockCopyReader, mockSourceHandler, mockHeader);
	}

	@Test
	public void testSynchronizeExistingRowsWithCopyCellUpdate() throws IOException {

		setFinalSchema(List.of(new Column().setName("one").setVectorIndex(0)));

		List<CopyRow> copyRows = List.of(new CopyRowImpl()
				.setCells(List.of(new CopyCell().setName("one").setValue(new ConValue(ConType.STRING, "c"))
						.setWasChangedByUser(true)))
				.setRgaNodeId(new LogicalTimestamp().setReplicaId(internalReplicaId).setSequenceNumber(1L))
				.setVectorNodeId(new LogicalTimestamp().setReplicaId(internalReplicaId).setSequenceNumber(2L)));

		when(mockCopyReader.getRows()).thenReturn(copyRows.iterator());
		when(mockSourceHandler.getRowKey(copyRows.get(0))).thenReturn("syn111");
		when(mockCopyReader.getInternalReplicaId()).thenReturn(internalReplicaId);

		SynchRow row = new SynchRow(Map.of("one", new ConValue(ConType.STRING, "b")), "syn111");
		try (RowReader rowReader = setupSourceRows(List.of(row))) {
			// call under test
			manager.synchronizeExistingRows(mockCopyReader, mockSourceHandler, mockIntendedChangePublisher,
					columnNameMap, rowReader);
		}
		// change is pushed to the source.
		verify(mockSourceHandler).applyCellChangesFromCopyToSource("syn111",
				Map.of("one", new ConValue(ConType.STRING, "c")));
		// the row is reset to the internal replica ID
		verify(mockIntendedChangePublisher).publish(new UpdateRowChange(copyRows.get(0).getVectorNodeId(),
				List.of(new ConValue(ConType.STRING, "c")), new Integer[] { 0 }));
		verifyNoMoreInteractions(mockIntendedChangePublisher, mockCopyReader, mockSourceHandler, mockHeader);
	}

	@Test
	public void testSynchronizeExistingRowsWithCopyCellUpdateAndNotFoundException() throws IOException {

		setFinalSchema(List.of(new Column().setName("one").setVectorIndex(0)));

		List<CopyRow> copyRows = List.of(
				// syn111
				new CopyRowImpl()
						.setCells(List.of(new CopyCell().setName("one").setValue(new ConValue(ConType.STRING, "c"))
								.setWasChangedByUser(true)))
						.setRgaNodeId(new LogicalTimestamp().setReplicaId(internalReplicaId).setSequenceNumber(2L))
						.setVectorNodeId(new LogicalTimestamp().setReplicaId(internalReplicaId).setSequenceNumber(3L)));

		when(mockCopyReader.getRows()).thenReturn(copyRows.iterator());
		when(mockSourceHandler.getRowKey(copyRows.get(0))).thenReturn("syn111");
		when(mockCopyReader.getInternalReplicaId()).thenReturn(internalReplicaId);
		doThrow(new NotFoundException("missing")).when(mockSourceHandler).applyCellChangesFromCopyToSource("syn111",
				Map.of("one", new ConValue(ConType.STRING, "c")));

		SynchRow row = new SynchRow(Map.of("one", new ConValue(ConType.STRING, "b")), "syn111");
		try (RowReader rowReader = setupSourceRows(List.of(row))) {
			// call under test
			manager.synchronizeExistingRows(mockCopyReader, mockSourceHandler, mockIntendedChangePublisher,
					columnNameMap, rowReader);
		}
		// The missing row should be removed from the copy
		verify(mockIntendedChangePublisher).publish(
				new DeleteRowChange(new LogicalTimestamp().setReplicaId(internalReplicaId).setSequenceNumber(2L)));
		verifyNoMoreInteractions(mockIntendedChangePublisher, mockCopyReader, mockSourceHandler, mockHeader);
	}

	@Test
	public void testSynchronizeExistingRowsWithCopyCellUpdateAndUnauthorizedException() throws IOException {

		setFinalSchema(List.of(new Column().setName("one").setVectorIndex(0)));

		List<CopyRow> copyRows = List.of(
				// syn111
				new CopyRowImpl()
						.setCells(List.of(new CopyCell().setName("one").setValue(new ConValue(ConType.STRING, "c"))
								.setWasChangedByUser(true)))
						.setRgaNodeId(new LogicalTimestamp().setReplicaId(internalReplicaId).setSequenceNumber(2L))
						.setVectorNodeId(new LogicalTimestamp().setReplicaId(internalReplicaId).setSequenceNumber(3L)));

		when(mockCopyReader.getRows()).thenReturn(copyRows.iterator());
		when(mockSourceHandler.getRowKey(copyRows.get(0))).thenReturn("syn111");
		when(mockCopyReader.getInternalReplicaId()).thenReturn(internalReplicaId);
		doThrow(new UnauthorizedException("no access")).when(mockSourceHandler)
				.applyCellChangesFromCopyToSource("syn111", Map.of("one", new ConValue(ConType.STRING, "c")));

		SynchRow row = new SynchRow(Map.of("one", new ConValue(ConType.STRING, "b")), "syn111");
		try (RowReader rowReader = setupSourceRows(List.of(row))) {
			// call under test
			manager.synchronizeExistingRows(mockCopyReader, mockSourceHandler, mockIntendedChangePublisher,
					columnNameMap, rowReader);
		}
		// the unaccessible row should be removed from the copy.
		verify(mockIntendedChangePublisher).publish(
				new DeleteRowChange(new LogicalTimestamp().setReplicaId(internalReplicaId).setSequenceNumber(2L)));
		verifyNoMoreInteractions(mockIntendedChangePublisher, mockCopyReader, mockSourceHandler, mockHeader);
	}

	@Test
	public void testSynchronizeExistingRowsWithRowDeletedInSource() throws IOException {

		setFinalSchema(List.of(new Column().setName("one").setVectorIndex(0)));

		List<CopyRow> copyRows = List.of(
				// syn111
				new CopyRowImpl()
						.setCells(List.of(new CopyCell().setName("one").setValue(new ConValue(ConType.STRING, "b"))
								.setWasChangedByUser(true)))
						.setRgaNodeId(new LogicalTimestamp().setReplicaId(internalReplicaId).setSequenceNumber(1L))
						.setVectorNodeId(new LogicalTimestamp().setReplicaId(internalReplicaId).setSequenceNumber(2L)),
				// syn222
				new CopyRowImpl()
						.setCells(List.of(new CopyCell().setName("one").setValue(new ConValue(ConType.STRING, "d"))
								.setWasChangedByUser(true)))
						.setRgaNodeId(new LogicalTimestamp().setReplicaId(internalReplicaId).setSequenceNumber(3L))
						.setVectorNodeId(new LogicalTimestamp().setReplicaId(internalReplicaId).setSequenceNumber(4L)));

		when(mockCopyReader.getRows()).thenReturn(copyRows.iterator());
		when(mockSourceHandler.getRowKey(copyRows.get(0))).thenReturn("syn111");
		when(mockSourceHandler.getRowKey(copyRows.get(1))).thenReturn("syn222");
		when(mockCopyReader.getInternalReplicaId()).thenReturn(internalReplicaId);

		SynchRow row = new SynchRow(Map.of("one", new ConValue(ConType.STRING, "b")), "syn111");
		try (RowReader rowReader = setupSourceRows(List.of(row))) {
			// call under test
			manager.synchronizeExistingRows(mockCopyReader, mockSourceHandler, mockIntendedChangePublisher,
					columnNameMap, rowReader);
		}

		verify(mockIntendedChangePublisher).publish(
				new DeleteRowChange(new LogicalTimestamp().setReplicaId(internalReplicaId).setSequenceNumber(3L)));
		verifyNoMoreInteractions(mockIntendedChangePublisher, mockCopyReader, mockSourceHandler, mockHeader);
	}

	@Test
	public void testSynchronizeExistingRowsWithRowAddedToCopy() throws IOException {

		setFinalSchema(List.of(new Column().setName("one").setVectorIndex(0)));

		List<CopyRow> copyRows = List.of(
				// syn111
				new CopyRowImpl()
						.setCells(List.of(new CopyCell().setName("one").setValue(new ConValue(ConType.STRING, "b"))
								.setWasChangedByUser(true)))
						.setRgaNodeId(new LogicalTimestamp().setReplicaId(internalReplicaId).setSequenceNumber(1L))
						.setVectorNodeId(new LogicalTimestamp().setReplicaId(internalReplicaId).setSequenceNumber(2L)),
				// syn222
				new CopyRowImpl()
						.setCells(List.of(new CopyCell().setName("one").setValue(new ConValue(ConType.STRING, "d"))
								.setWasChangedByUser(true)))
						.setRgaNodeId(new LogicalTimestamp().setReplicaId(userReplciaId).setSequenceNumber(3L))
						.setVectorNodeId(new LogicalTimestamp().setReplicaId(internalReplicaId).setSequenceNumber(4L)));

		when(mockCopyReader.getRows()).thenReturn(copyRows.iterator());
		when(mockSourceHandler.getRowKey(copyRows.get(0))).thenReturn("syn111");
		when(mockSourceHandler.getRowKey(copyRows.get(1))).thenReturn("syn222");
		when(mockCopyReader.getInternalReplicaId()).thenReturn(internalReplicaId);

		SynchRow row = new SynchRow(Map.of("one", new ConValue(ConType.STRING, "b")), "syn111");
		try (RowReader rowReader = setupSourceRows(List.of(row))) {
			// call under test
			manager.synchronizeExistingRows(mockCopyReader, mockSourceHandler, mockIntendedChangePublisher,
					columnNameMap, rowReader);
		}

		// the new row should be added to the source.
		verify(mockSourceHandler)
				.addNewRowToSource(new SynchRow(Map.of("one", new ConValue(ConType.STRING, "d")), "syn222"));
		verifyNoMoreInteractions(mockIntendedChangePublisher, mockCopyReader, mockSourceHandler, mockHeader);
	}

	@Test
	public void testAddRemainingSourceRowsWithNewRow() throws IOException {

		setFinalSchema(List.of(new Column().setName("one").setVectorIndex(5)));

		LogicalTimestamp lastRgaNodeId = new LogicalTimestamp().setReplicaId(internalReplicaId).setSequenceNumber(5L);
		when(mockCopyReader.getLastRgaNodeId()).thenReturn(lastRgaNodeId);
		LogicalTimestamp rowsId = new LogicalTimestamp();
		when(mockHeader.getRowsId()).thenReturn(rowsId);
		when(mockCopyReader.getHeader()).thenReturn(mockHeader);

		try (RowReader rowReader = setupSourceRows(
				List.of(new SynchRow(Map.of("one", new ConValue(ConType.STRING, "b")), "syn111"),
						new SynchRow(Map.of("one", new ConValue(ConType.STRING, "d")), "syn222"),
						new SynchRow(Map.of("one", new ConValue(ConType.STRING, "e")), "syn333")))) {
			// first two rows are consumed.
			rowReader.removeRow("syn111");
			rowReader.removeRow("syn222");
			// call under test
			manager.addRemainingSourceRows(mockCopyReader, rowReader, mockIntendedChangePublisher, columnNameMap);
		}

		// the new row should be added to the copy.
		verify(mockIntendedChangePublisher).publish(new InsertRowChange(rowsId, lastRgaNodeId,
				List.of(new ConValue(ConType.STRING, "e")), new Integer[] { 5 }));

		verifyNoMoreInteractions(mockIntendedChangePublisher, mockCopyReader, mockSourceHandler, mockHeader);
	}

	@Test
	public void testAddRemainingSourceRowsWithAllConsumed() throws IOException {

		setFinalSchema(List.of(new Column().setName("one").setVectorIndex(5)));

		LogicalTimestamp lastRgaNodeId = new LogicalTimestamp().setReplicaId(internalReplicaId).setSequenceNumber(5L);
		when(mockCopyReader.getLastRgaNodeId()).thenReturn(lastRgaNodeId);
		LogicalTimestamp rowsId = new LogicalTimestamp();
		when(mockHeader.getRowsId()).thenReturn(rowsId);
		when(mockCopyReader.getHeader()).thenReturn(mockHeader);

		try (RowReader rowReader = setupSourceRows(
				List.of(new SynchRow(Map.of("one", new ConValue(ConType.STRING, "b")), "syn111"),
						new SynchRow(Map.of("one", new ConValue(ConType.STRING, "d")), "syn222"),
						new SynchRow(Map.of("one", new ConValue(ConType.STRING, "e")), "syn333")))) {
			// all three rows are consumed.
			rowReader.removeRow("syn111");
			rowReader.removeRow("syn222");
			rowReader.removeRow("syn333");
			// call under test
			manager.addRemainingSourceRows(mockCopyReader, rowReader, mockIntendedChangePublisher, columnNameMap);
		}

		verifyNoMoreInteractions(mockIntendedChangePublisher, mockCopyReader, mockSourceHandler, mockHeader);
	}

	RowReader setupSourceRows(List<SynchRow> rows) throws IOException {
		File temp = File.createTempFile("GridSynchronizationManagerImplTest", ".bin");
		temp.deleteOnExit();
		List<DiskPointer> diskPointers = new ArrayList<>();
		try (RowWriter writer = new RowWriter(new BufferedOutputStream(new FileOutputStream(temp)))) {
			rows.forEach(r -> {
				diskPointers.add(writer.nextRow(r));
			});
		}
		return new RowReader(diskPointers, new RandomAccessFile(temp, "r"));
	}
}
