package org.sagebionetworks.repo.manager.grid.synch.row;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.manager.grid.internal.replica.change.DeleteArrayNodeChange;
import org.sagebionetworks.repo.manager.grid.internal.replica.change.InsertRowChange;
import org.sagebionetworks.repo.manager.grid.internal.replica.change.IntendedChangePublisher;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.Column;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.GridHeader;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.SynapseRow;
import org.sagebionetworks.repo.manager.grid.synch.handler.CopyHandler;
import org.sagebionetworks.repo.manager.grid.synch.handler.SourceHandler;
import org.sagebionetworks.repo.manager.grid.synch.io.RowSourceItem;
import org.sagebionetworks.repo.manager.grid.synch.io.RowSourceItemReference;
import org.sagebionetworks.repo.model.grid.patch.ConType;
import org.sagebionetworks.repo.model.grid.patch.ConValue;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;

@ExtendWith(MockitoExtension.class)
public class RowCopyImplTest {

	@Mock
	private CopyHandler mockCopyHandler;
	@Mock
	private GridHeader mockGridHeader;
	@Mock
	private RowSourceItemReference mockRowHeader;
	@Mock
	private SourceHandler mockSourceHandler;
	@Mock
	private IntendedChangePublisher mockPublisher;

	private final LogicalTimestamp rowsArrayId = new LogicalTimestamp().setReplicaId(1L).setSequenceNumber(2L);
	private final LogicalTimestamp lastRowId = new LogicalTimestamp().setReplicaId(3L).setSequenceNumber(4L);

	private final List<Column> finalSchema = List.of(new Column().setName("a").setVectorIndex(0),
			new Column().setName("b").setVectorIndex(1));

	RowCopyImpl setupCopy() {
		when(mockCopyHandler.getHeader()).thenReturn(mockGridHeader);
		when(mockGridHeader.getRowsId()).thenReturn(rowsArrayId);
		when(mockCopyHandler.getLastRowsRgaNodeId()).thenReturn(lastRowId);
		return new RowCopyImpl(finalSchema, mockPublisher, mockCopyHandler, mockSourceHandler);
	}

	@Test
	public void testWasDeletedByUserDelegatesToSourceHandlerBaseline() {
		RowCopyImpl copy = setupCopy();
		when(mockSourceHandler.wasInSyncedBaseline("k1")).thenReturn(true);
		when(mockSourceHandler.wasInSyncedBaseline("k2")).thenReturn(false);

		// call under test — a row absent from the grid was deleted by the user iff its
		// key was in the synced baseline AND the source row has not changed since then.
		assertTrue(copy.wasDeletedByUser("k1"));
		assertFalse(copy.wasDeletedByUser("k2"));
	}

	@Test
	public void testWasDeletedByUserWhenSourceChangedSinceBaseline() {
		RowCopyImpl copy = setupCopy();
		when(mockSourceHandler.wasInSyncedBaseline("k1")).thenReturn(true);
		when(mockSourceHandler.changedSinceBaseline("k1")).thenReturn(true);

		// call under test — the user deleted this row, but the source row changed
		// between the synced revision and the new revision, so it is NOT treated as a
		// user deletion (it will be re-imported into the grid instead).
		assertFalse(copy.wasDeletedByUser("k1"));
	}

	@Test
	public void testStreamItems() {
		List<RowCopyItem> rows = List.of(new RowCopyItemImpl().setSynapseRow(new SynapseRow().setRowId(111L)));
		when(mockCopyHandler.getRows()).thenReturn(rows.iterator());
		RowCopyImpl copy = setupCopy();
		// call under test
		List<RowCopyItem> results = copy.streamItems().collect(Collectors.toList());
		assertEquals(rows, results);

		verify(mockSourceHandler, never()).onSurvivingRow(any());
		verifyNoMoreInteractions(mockPublisher);
	}

	@Test
	public void testStreamItemsExcludesFrozenRowsAndReportsSurvivingRow() {
		ConValue a = new ConValue(ConType.STRING, "x");
		RowCopyItemImpl frozen = new RowCopyItemImpl()
				.setCells(List.of(new CellCopyItem().setName("a").setValue(a)));
		RowCopyItemImpl kept = new RowCopyItemImpl().setSynapseRow(new SynapseRow().setRowId(111L));
		when(mockCopyHandler.getRows()).thenReturn(List.<RowCopyItem>of(frozen, kept).iterator());
		when(mockSourceHandler.isFrozenCopyRow(frozen)).thenReturn(true);
		when(mockSourceHandler.isFrozenCopyRow(kept)).thenReturn(false);
		RowCopyImpl copy = setupCopy();

		// call under test — a frozen (keyless) grid row is excluded from Phase 1 so it
		// is never matched, merged, or removed, but still survives, so it is reported to
		// the source handler.
		List<RowCopyItem> results = copy.streamItems().collect(Collectors.toList());

		assertEquals(List.of(kept), results);
		verify(mockSourceHandler).onSurvivingRow(Map.of("a", a));
		verify(mockSourceHandler, never()).getRowKey(eq(frozen));
		verifyNoMoreInteractions(mockPublisher);
	}

	@Test
	public void testStreamItemsExcludesDuplicateKeyedRowsAndReportsSurvivingRow() {
		ConValue first = new ConValue(ConType.STRING, "x");
		ConValue second = new ConValue(ConType.STRING, "y");
		// Two complete-key grid rows that resolve to the same source key.
		RowCopyItemImpl firstOccurrence = new RowCopyItemImpl()
				.setCells(List.of(new CellCopyItem().setName("a").setValue(first)));
		RowCopyItemImpl duplicate = new RowCopyItemImpl()
				.setCells(List.of(new CellCopyItem().setName("a").setValue(second)));
		when(mockCopyHandler.getRows()).thenReturn(List.<RowCopyItem>of(firstOccurrence, duplicate).iterator());
		// Neither row is frozen for an incomplete upsert key.
		when(mockSourceHandler.isFrozenCopyRow(firstOccurrence)).thenReturn(false);
		when(mockSourceHandler.isFrozenCopyRow(duplicate)).thenReturn(false);
		// Both rows resolve to the same source key, so they are duplicates of one another.
		when(mockSourceHandler.getRowKey(firstOccurrence)).thenReturn("k");
		when(mockSourceHandler.getRowKey(duplicate)).thenReturn("k");
		RowCopyImpl copy = setupCopy();

		// call under test — when two grid rows share one source key, the first occurrence
		// is kept for Phase 1 matching (merge the first), while every later duplicate is
		// frozen: excluded from the keyed traversal so it is never matched, merged, or
		// removed, but still reported as a surviving row so the duplicate is not dropped
		// from the grid.
		List<RowCopyItem> results = copy.streamItems().collect(Collectors.toList());

		assertEquals(List.of(firstOccurrence), results);
		verify(mockSourceHandler).onSurvivingRow(Map.of("a", second));
		// only the duplicate is reported as surviving here; the first occurrence is
		// reported later by the engine when it is matched/retained.
		verify(mockSourceHandler, times(1)).onSurvivingRow(any());
		verifyNoMoreInteractions(mockPublisher);
	}

	@Test
	public void testRemoveItem() {
		RowCopyImpl copy = setupCopy();
		LogicalTimestamp rgaNodeId = new LogicalTimestamp().setReplicaId(5L).setSequenceNumber(6L);
		RowCopyItemImpl item = new RowCopyItemImpl().setRgaNodeId(rgaNodeId);

		// call under test — a row deleted from the source is removed from the grid; a
		// removed row is not a surviving row.
		copy.removeItem(item);

		verify(mockPublisher).publish(new DeleteArrayNodeChange(rowsArrayId, rgaNodeId));
		verify(mockSourceHandler, never()).onSurvivingRow(any());
	}

	@Test
	public void testAddItem() {
		RowCopyImpl copy = setupCopy();
		SynapseRow synRow = new SynapseRow().setRowId(123L).setVersionNumber(0L).setEtag("e1");
		ConValue firstCellValue = new ConValue(ConType.LONG, 222L);
		ConValue secondCellValue = new ConValue(ConType.STRING, "other");
		TreeMap<String, ConValue> data = new TreeMap<>(Map.of("a", firstCellValue, "b", secondCellValue));
		when(mockRowHeader.fetchRow()).thenReturn(new RowSourceItem(data, "syn123", synRow));

		// call under test — a pulled-in source row is inserted into the grid and
		// reported as a surviving row.
		copy.addItem(mockRowHeader);

		verify(mockPublisher).publish(new InsertRowChange(rowsArrayId, lastRowId,
				List.of(firstCellValue, secondCellValue), new Integer[] { 0, 1 }, synRow.toConValue()));
		verify(mockSourceHandler).onSurvivingRow(data);
	}

	@Test
	public void testOnItemRetained() {
		RowCopyImpl copy = setupCopy();
		ConValue a = new ConValue(ConType.STRING, "x");
		ConValue b = new ConValue(ConType.STRING, "y");
		RowCopyItemImpl copyItem = new RowCopyItemImpl().setCells(
				List.of(new CellCopyItem().setName("a").setValue(a), new CellCopyItem().setName("b").setValue(b)));

		// call under test — an unchanged row still survives and is reported to the
		// source handler, with no grid mutation.
		copy.onItemRetained(copyItem, mockRowHeader);

		verify(mockSourceHandler).onSurvivingRow(Map.of("a", a, "b", b));
		verifyNoMoreInteractions(mockPublisher);
	}

}
