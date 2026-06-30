package org.sagebionetworks.repo.manager.grid.synch.row;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.manager.grid.internal.replica.change.DeleteArrayNodeChange;
import org.sagebionetworks.repo.manager.grid.internal.replica.change.IntendedChange;
import org.sagebionetworks.repo.manager.grid.internal.replica.change.IntendedChangePublisher;
import org.sagebionetworks.repo.manager.grid.internal.replica.change.UpdateRowChange;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.Column;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.GridHeader;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.SynapseRow;
import org.sagebionetworks.repo.manager.grid.synch.core.SynchronizationLogic;
import org.sagebionetworks.repo.manager.grid.synch.handler.CopyHandler;
import org.sagebionetworks.repo.manager.grid.synch.handler.SourceHandler;
import org.sagebionetworks.repo.manager.grid.synch.io.RowSourceItem;
import org.sagebionetworks.repo.manager.grid.synch.io.RowSourceItemReference;
import org.sagebionetworks.repo.model.grid.patch.ConType;
import org.sagebionetworks.repo.model.grid.patch.ConValue;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;
import org.sagebionetworks.repo.web.NotFoundException;

@ExtendWith(MockitoExtension.class)
public class RowMergeImplTest {

	@Mock
	private SourceHandler mockSourceHandler;
	@Mock
	private IntendedChangePublisher mockIntendedChangePublisher;
	@Mock
	private CopyHandler mockCopyHandler;
	@Mock
	private GridHeader mockGridHeader;
	@Mock
	private RowSourceItemReference mockRowSourceItemReference;

	private List<Column> finalSchema;
	private SynchronizationLogic logic;
	private String rowKey;

	private ConValue c1;
	private ConValue c2;
	private ConValue c1d;
	private ConValue c3;
	private SynapseRow synRow;
	private LogicalTimestamp rowVectorId;
	private LogicalTimestamp rgaNodeId;
	private LogicalTimestamp metadataNodeId;
	private LogicalTimestamp rowsArrayId;

	@BeforeEach
	public void before() {
		finalSchema = List.of(new Column().setName("a").setVectorIndex(1), new Column().setName("b").setVectorIndex(0));
		logic = new SynchronizationLogic();
		rowKey = "syn123";
		c1 = new ConValue(ConType.STRING, "one");
		c1d = new ConValue(ConType.STRING, "two");
		c2 = new ConValue(ConType.BOOLEAN, true);
		c3 = new ConValue(ConType.LONG, 45L);
		synRow = new SynapseRow().setRowId(123L).setVersionNumber(0L).setEtag("e1");

		rowVectorId = new LogicalTimestamp().setReplicaId(555L).setSequenceNumber(3L);
		rgaNodeId = new LogicalTimestamp().setReplicaId(555L).setSequenceNumber(4L);
		metadataNodeId = new LogicalTimestamp().setReplicaId(555L).setSequenceNumber(5L);
		rowsArrayId = new LogicalTimestamp().setReplicaId(555L).setSequenceNumber(6L);
	}

	RowMergeImpl setupRowMerge() {
		return setupRowMerge(false);
	}

	RowMergeImpl setupRowMerge(boolean preserveUserAttribution) {
		when(mockCopyHandler.getHeader()).thenReturn(mockGridHeader);
		when(mockGridHeader.getRowsId()).thenReturn(rowsArrayId);
		return new RowMergeImpl(logic, mockSourceHandler, mockIntendedChangePublisher, mockCopyHandler, finalSchema,
				preserveUserAttribution);
	}

	RowCopyItemImpl copyItem(List<CellCopyItem> cells) {
		return new RowCopyItemImpl().setVectorNodeId(rowVectorId).setRgaNodeId(rgaNodeId)
				.setMetadataNodeId(metadataNodeId).setCells(cells).setSynapseRow(synRow);
	}

	/**
	 * Capture the single published change, assert it is an UpdateRowChange whose
	 * (vectorIndex -> value) content matches the expected merged cells (minus the
	 * excluded cells) mapped through the final schema. Order-independent, since the
	 * production code iterates a HashMap.
	 */
	void verifyUpdatePublished(Map<String, ConValue> mergedCells, Set<String> excludedCells, ConValue synapseRow) {
		ArgumentCaptor<IntendedChange> captor = ArgumentCaptor.forClass(IntendedChange.class);
		verify(mockIntendedChangePublisher).publish(captor.capture());
		UpdateRowChange change = (UpdateRowChange) captor.getValue();
		assertEquals(rowVectorId, change.getRowVectorId());
		assertEquals(Optional.ofNullable(metadataNodeId), change.getMetadataObjectId());
		assertEquals(Optional.ofNullable(synapseRow), change.getSynapseRow());
		assertEquals(expectedIndexed(mergedCells, excludedCells), actualIndexed(change));
	}

	Map<Integer, ConValue> expectedIndexed(Map<String, ConValue> mergedCells, Set<String> excludedCells) {
		Map<String, Column> colMap = finalSchema.stream().collect(Collectors.toMap(Column::getName, c -> c));
		Map<Integer, ConValue> out = new HashMap<>();
		mergedCells.forEach((name, value) -> {
			if (!excludedCells.contains(name)) {
				Column col = colMap.get(name);
				if (col != null) {
					out.put(col.getVectorIndex(), value);
				}
			}
		});
		return out;
	}

	Map<Integer, ConValue> actualIndexed(UpdateRowChange change) {
		Map<Integer, ConValue> out = new HashMap<>();
		Integer[] idx = change.getRowVectorIndex();
		List<ConValue> data = change.getRowData();
		for (int i = 0; i < idx.length; i++) {
			out.put(idx[i], data.get(i));
		}
		return out;
	}

	@Test
	public void testMerge() {
		RowMergeImpl merge = setupRowMerge();
		RowSourceItem sourceItem = new RowSourceItem(new TreeMap<>(Map.of("a", c1, "b", c2)), rowKey, synRow);
		RowCopyItemImpl copyItem = copyItem(List.of(new CellCopyItem().setName("a").setValue(c1).setWasChangedByUser(false),
				new CellCopyItem().setName("b").setValue(c2).setWasChangedByUser(false)));
		when(mockRowSourceItemReference.fetchRow()).thenReturn(sourceItem);

		// call under test
		merge.merge(rowKey, copyItem, mockRowSourceItemReference);

		verifyUpdatePublished(Map.of("a", c1, "b", c2), Set.of(), synRow.toConValue());
		verify(mockSourceHandler).onSurvivingRow(Map.of("a", c1, "b", c2));
		verify(mockSourceHandler, never()).applyCellChangesFromCopyToSource(anyString(), any());
	}

	@Test
	public void testMergeWithCellUpdatedInSource() {
		RowMergeImpl merge = setupRowMerge();
		RowSourceItem sourceItem = new RowSourceItem(new TreeMap<>(Map.of("a", c1d, "b", c2)), rowKey, synRow);
		RowCopyItemImpl copyItem = copyItem(List.of(new CellCopyItem().setName("a").setValue(c1).setWasChangedByUser(false),
				new CellCopyItem().setName("b").setValue(c2).setWasChangedByUser(false)));
		when(mockRowSourceItemReference.fetchRow()).thenReturn(sourceItem);

		// call under test — the source's newer value for "a" is pulled into the grid.
		merge.merge(rowKey, copyItem, mockRowSourceItemReference);

		verifyUpdatePublished(Map.of("a", c1d, "b", c2), Set.of(), synRow.toConValue());
		verify(mockSourceHandler).onSurvivingRow(Map.of("a", c1d, "b", c2));
		verify(mockSourceHandler, never()).applyCellChangesFromCopyToSource(anyString(), any());
	}

	@Test
	public void testMergeWithCellUpdatedInCopy() {
		RowMergeImpl merge = setupRowMerge();
		RowSourceItem sourceItem = new RowSourceItem(new TreeMap<>(Map.of("a", c1, "b", c2)), rowKey, synRow);
		RowCopyItemImpl copyItem = copyItem(List.of(new CellCopyItem().setName("a").setValue(c1d).setWasChangedByUser(true),
				new CellCopyItem().setName("b").setValue(c2).setWasChangedByUser(false)));
		when(mockRowSourceItemReference.fetchRow()).thenReturn(sourceItem);

		// call under test — the user-changed cell wins and is written back to the
		// source (PULL_PUSH); the whole merged row is rewritten in the grid.
		merge.merge(rowKey, copyItem, mockRowSourceItemReference);

		verify(mockSourceHandler).applyCellChangesFromCopyToSource(rowKey, Map.of("a", c1d));
		verifyUpdatePublished(Map.of("a", c1d, "b", c2), Set.of(), synRow.toConValue());
		verify(mockSourceHandler).onSurvivingRow(Map.of("a", c1d, "b", c2));
	}

	@Test
	public void testMergeWithCellAddedToCopy() {
		finalSchema = List.of(new Column().setName("a").setVectorIndex(1), new Column().setName("b").setVectorIndex(0),
				new Column().setName("c").setVectorIndex(2));
		RowMergeImpl merge = setupRowMerge();
		RowSourceItem sourceItem = new RowSourceItem(new TreeMap<>(Map.of("a", c1, "b", c2)), rowKey, synRow);
		RowCopyItemImpl copyItem = copyItem(List.of(new CellCopyItem().setName("a").setValue(c1).setWasChangedByUser(false),
				new CellCopyItem().setName("b").setValue(c2).setWasChangedByUser(false),
				new CellCopyItem().setName("c").setValue(c3).setWasChangedByUser(true)));
		when(mockRowSourceItemReference.fetchRow()).thenReturn(sourceItem);

		// call under test
		merge.merge(rowKey, copyItem, mockRowSourceItemReference);

		verify(mockSourceHandler).applyCellChangesFromCopyToSource(rowKey, Map.of("c", c3));
		verifyUpdatePublished(Map.of("a", c1, "b", c2, "c", c3), Set.of(), synRow.toConValue());
		verify(mockSourceHandler).onSurvivingRow(Map.of("a", c1, "b", c2, "c", c3));
	}

	@Test
	public void testMergeWithCellAddedToSource() {
		finalSchema = List.of(new Column().setName("a").setVectorIndex(1), new Column().setName("b").setVectorIndex(0),
				new Column().setName("c").setVectorIndex(2));
		RowMergeImpl merge = setupRowMerge();
		RowSourceItem sourceItem = new RowSourceItem(new TreeMap<>(Map.of("a", c1, "b", c2, "c", c3)), rowKey, synRow);
		RowCopyItemImpl copyItem = copyItem(List.of(new CellCopyItem().setName("a").setValue(c1).setWasChangedByUser(false),
				new CellCopyItem().setName("b").setValue(c2).setWasChangedByUser(false)));
		when(mockRowSourceItemReference.fetchRow()).thenReturn(sourceItem);

		// call under test
		merge.merge(rowKey, copyItem, mockRowSourceItemReference);

		verifyUpdatePublished(Map.of("a", c1, "b", c2, "c", c3), Set.of(), synRow.toConValue());
		verify(mockSourceHandler).onSurvivingRow(Map.of("a", c1, "b", c2, "c", c3));
		verify(mockSourceHandler, never()).applyCellChangesFromCopyToSource(anyString(), any());
	}

	@Test
	public void testMergeWithCellUpdatedAndNotInFinalSchema() {
		finalSchema = List.of(new Column().setName("a").setVectorIndex(1), new Column().setName("c").setVectorIndex(2));
		RowMergeImpl merge = setupRowMerge();
		RowSourceItem sourceItem = new RowSourceItem(new TreeMap<>(Map.of("a", c1, "b", c2, "c", c3)), rowKey, synRow);
		RowCopyItemImpl copyItem = copyItem(List.of(new CellCopyItem().setName("a").setValue(c1).setWasChangedByUser(false),
				new CellCopyItem().setName("b").setValue(new ConValue(ConType.STRING, "deleted"))
						.setWasChangedByUser(true)));
		when(mockRowSourceItemReference.fetchRow()).thenReturn(sourceItem);

		// call under test — "b" is not in the final schema; the user's edit ("deleted")
		// is silently discarded
		merge.merge(rowKey, copyItem, mockRowSourceItemReference);

		verifyUpdatePublished(Map.of("a", c1, "b", c2, "c", c3), Set.of(), synRow.toConValue());
		// The source's value for "b" (c2) is still present in the merged cells reported to onSurvivingRow.
		// The grid patch omits "b" because it has no vector index in the final schema.
		verify(mockSourceHandler).onSurvivingRow(Map.of("a", c1, "b", c2, "c", c3));
		verify(mockSourceHandler, never()).applyCellChangesFromCopyToSource(anyString(), any());
	}

	@Test
	public void testMergeWithNoSynapseRow() {
		RowMergeImpl merge = setupRowMerge();
		this.synRow = null;
		RowSourceItem sourceItem = new RowSourceItem(new TreeMap<>(Map.of("a", c1, "b", c2)), rowKey, (SynapseRow) null);
		RowCopyItemImpl copyItem = new RowCopyItemImpl().setVectorNodeId(rowVectorId).setRgaNodeId(rgaNodeId)
				.setMetadataNodeId(metadataNodeId)
				.setCells(List.of(new CellCopyItem().setName("a").setValue(c1).setWasChangedByUser(false),
						new CellCopyItem().setName("b").setValue(c2).setWasChangedByUser(false)));
		when(mockRowSourceItemReference.fetchRow()).thenReturn(sourceItem);

		// call under test
		merge.merge(rowKey, copyItem, mockRowSourceItemReference);

		verifyUpdatePublished(Map.of("a", c1, "b", c2), Set.of(), null);
		verify(mockSourceHandler).onSurvivingRow(Map.of("a", c1, "b", c2));
	}

	/**
	 * PULL (preserveUserAttribution=true): a user-winning cell that diverges from
	 * the source is omitted from the grid rewrite so its user-owned node — and thus
	 * attribution — is preserved, but it is still written back to the source and
	 * still reported as a surviving row with its full merged value.
	 */
	@Test
	public void testMergeWithUserChangedCellAndPreserveUserAttribution() {
		RowMergeImpl merge = setupRowMerge(true);
		RowSourceItem sourceItem = new RowSourceItem(new TreeMap<>(Map.of("a", c1, "b", c3)), rowKey, synRow);
		RowCopyItemImpl copyItem = copyItem(List.of(new CellCopyItem().setName("a").setValue(c1d).setWasChangedByUser(true),
				new CellCopyItem().setName("b").setValue(c2).setWasChangedByUser(false)));
		when(mockRowSourceItemReference.fetchRow()).thenReturn(sourceItem);

		// call under test
		merge.merge(rowKey, copyItem, mockRowSourceItemReference);

		verify(mockSourceHandler).applyCellChangesFromCopyToSource(rowKey, Map.of("a", c1d));
		// "a" is the user-winning cell -> omitted from the grid rewrite; "b" took source.
		verifyUpdatePublished(Map.of("a", c1d, "b", c3), Set.of("a"), synRow.toConValue());
		verify(mockSourceHandler).onSurvivingRow(Map.of("a", c1d, "b", c3));
	}

	/**
	 * PULL: a user edit that coincides with the source value is a matched-equal cell
	 * (never enters the conflict branch), so it is NOT excluded — the divergence
	 * semantic. The whole row is rewritten under the service replica.
	 */
	@Test
	public void testMergeWithCoincidentalUserMatchAndPreserveUserAttribution() {
		RowMergeImpl merge = setupRowMerge(true);
		RowSourceItem sourceItem = new RowSourceItem(new TreeMap<>(Map.of("a", c1, "b", c3)), rowKey, synRow);
		RowCopyItemImpl copyItem = copyItem(List.of(new CellCopyItem().setName("a").setValue(c1).setWasChangedByUser(true),
				new CellCopyItem().setName("b").setValue(c2).setWasChangedByUser(false)));
		when(mockRowSourceItemReference.fetchRow()).thenReturn(sourceItem);

		// call under test
		merge.merge(rowKey, copyItem, mockRowSourceItemReference);

		verifyUpdatePublished(Map.of("a", c1, "b", c3), Set.of(), synRow.toConValue());
		verify(mockSourceHandler).onSurvivingRow(Map.of("a", c1, "b", c3));
		verify(mockSourceHandler, never()).applyCellChangesFromCopyToSource(anyString(), any());
	}

	/**
	 * PULL with every writable cell user-won: the grid rewrite would be empty, so no
	 * (empty) update is published — but the surviving row is still reported.
	 */
	@Test
	public void testMergeWithAllCellsUserChangedAndPreserveUserAttribution() {
		finalSchema = List.of(new Column().setName("a").setVectorIndex(1));
		RowMergeImpl merge = setupRowMerge(true);
		RowSourceItem sourceItem = new RowSourceItem(new TreeMap<>(Map.of("a", c1)), rowKey, synRow);
		RowCopyItemImpl copyItem = copyItem(
				List.of(new CellCopyItem().setName("a").setValue(c1d).setWasChangedByUser(true)));
		when(mockRowSourceItemReference.fetchRow()).thenReturn(sourceItem);

		// call under test
		merge.merge(rowKey, copyItem, mockRowSourceItemReference);

		verify(mockSourceHandler).applyCellChangesFromCopyToSource(rowKey, Map.of("a", c1d));
		verify(mockIntendedChangePublisher, never()).publish(any());
		verify(mockSourceHandler).onSurvivingRow(Map.of("a", c1d));
	}

	/**
	 * A non-fatal source write failure (IllegalArgumentException) leaves the grid row
	 * as-is: no update is published and the row is not reported as a survivor.
	 */
	@Test
	public void testMergeWhenSourceWriteThrowsIllegalArgument() {
		RowMergeImpl merge = setupRowMerge();
		RowSourceItem sourceItem = new RowSourceItem(new TreeMap<>(Map.of("a", c1, "b", c2)), rowKey, synRow);
		RowCopyItemImpl copyItem = copyItem(List.of(new CellCopyItem().setName("a").setValue(c1d).setWasChangedByUser(true),
				new CellCopyItem().setName("b").setValue(c2).setWasChangedByUser(false)));
		when(mockRowSourceItemReference.fetchRow()).thenReturn(sourceItem);
		doThrow(new IllegalArgumentException("bad")).when(mockSourceHandler)
				.applyCellChangesFromCopyToSource(rowKey, Map.of("a", c1d));

		// call under test
		merge.merge(rowKey, copyItem, mockRowSourceItemReference);

		verify(mockIntendedChangePublisher, never()).publish(any());
		verify(mockSourceHandler, never()).onSurvivingRow(any());
	}

	/**
	 * When the source row is gone (NotFoundException) the grid row is removed for
	 * consistency and is not reported as a survivor.
	 */
	@Test
	public void testMergeWhenSourceRowGone() {
		RowMergeImpl merge = setupRowMerge();
		RowSourceItem sourceItem = new RowSourceItem(new TreeMap<>(Map.of("a", c1, "b", c2)), rowKey, synRow);
		RowCopyItemImpl copyItem = copyItem(List.of(new CellCopyItem().setName("a").setValue(c1d).setWasChangedByUser(true),
				new CellCopyItem().setName("b").setValue(c2).setWasChangedByUser(false)));
		when(mockRowSourceItemReference.fetchRow()).thenReturn(sourceItem);
		doThrow(new NotFoundException("gone")).when(mockSourceHandler)
				.applyCellChangesFromCopyToSource(rowKey, Map.of("a", c1d));

		// call under test
		merge.merge(rowKey, copyItem, mockRowSourceItemReference);

		verify(mockIntendedChangePublisher).publish(new DeleteArrayNodeChange(rowsArrayId, rgaNodeId));
		verify(mockSourceHandler, never()).onSurvivingRow(any());
	}

}
