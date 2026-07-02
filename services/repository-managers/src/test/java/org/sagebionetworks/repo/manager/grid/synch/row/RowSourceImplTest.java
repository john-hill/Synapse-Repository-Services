package org.sagebionetworks.repo.manager.grid.synch.row;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.SynapseRow;
import org.sagebionetworks.repo.manager.grid.synch.handler.SourceHandler;
import org.sagebionetworks.repo.manager.grid.synch.io.RowSourceItem;
import org.sagebionetworks.repo.manager.grid.synch.io.RowSourceItemReader;
import org.sagebionetworks.repo.manager.grid.synch.io.RowSourceItemReference;
import org.sagebionetworks.repo.model.grid.patch.ConType;
import org.sagebionetworks.repo.model.grid.patch.ConValue;

@ExtendWith(MockitoExtension.class)
public class RowSourceImplTest {

	@Mock
	private SourceHandler mockSourceHandler;
	@Mock
	private RowSourceItemReader mockRowReader;
	@Mock
	private RowSourceItemReference mockRowHeader;

	@InjectMocks
	private RowSourceImpl source;

	@Test
	public void testAddItem() {
		ConValue c1 = new ConValue(ConType.STRING, "one");
		ConValue c2 = new ConValue(ConType.BOOLEAN, true);
		RowCopyItemImpl copyItem = new RowCopyItemImpl().setCells(
				List.of(new CellCopyItem().setName("a").setValue(c1), new CellCopyItem().setName("b").setValue(c2)));
		when(mockSourceHandler.getRowKey(copyItem)).thenReturn("theKey");

		// call under test
		source.addItem(copyItem);

		verify(mockSourceHandler).getRowKey(copyItem);
		verify(mockSourceHandler).addNewRowToSource(new RowSourceItem(new TreeMap<>(Map.of("a", c1, "b", c2)), "theKey"));
		verifyNoMoreInteractionsWithAllMocks();
	}

	private void verifyNoMoreInteractionsWithAllMocks() {
		verifyNoMoreInteractions(mockRowHeader, mockSourceHandler);
	}

	@Test
	public void testAddItemWithSynapseRow() {
		ConValue c1 = new ConValue(ConType.STRING, "one");
		ConValue c2 = new ConValue(ConType.BOOLEAN, true);
		SynapseRow synRow = new SynapseRow().setRowId(123L).setVersionNumber(0L).setEtag("e1");
		RowCopyItemImpl copyItem = new RowCopyItemImpl().setCells(
				List.of(new CellCopyItem().setName("a").setValue(c1), new CellCopyItem().setName("b").setValue(c2)))
				.setSynapseRow(synRow);
		when(mockSourceHandler.getRowKey(copyItem)).thenReturn("theKey");

		// call under test
		source.addItem(copyItem);

		verify(mockSourceHandler).getRowKey(copyItem);
		verify(mockSourceHandler)
				.addNewRowToSource(new RowSourceItem(new TreeMap<>(Map.of("a", c1, "b", c2)), "theKey", synRow));
		verifyNoMoreInteractionsWithAllMocks();
	}

	@Test
	public void testRemoveItem() {
		ConValue c1 = new ConValue(ConType.STRING, "one");
		ConValue c2 = new ConValue(ConType.BOOLEAN, true);
		SynapseRow synRow = new SynapseRow().setRowId(123L).setVersionNumber(0L).setEtag("e1");
		RowSourceItem synch = new RowSourceItem(new TreeMap<>(Map.of("a", c1, "b", c2)), "theKey", synRow);
		when(mockRowHeader.fetchRow()).thenReturn(synch);

		// call under test
		source.removeItem(mockRowHeader);

		verify(mockSourceHandler).removeRow(synch);
		verify(mockRowHeader).fetchRow();
		verifyNoMoreInteractionsWithAllMocks();
	}

	@Test
	public void testRemoveItemWithNullSynapseRow() {
		ConValue c1 = new ConValue(ConType.STRING, "one");
		ConValue c2 = new ConValue(ConType.BOOLEAN, true);
		RowSourceItem synch = new RowSourceItem(new TreeMap<>(Map.of("a", c1, "b", c2)), "theKey", (SynapseRow) null);
		when(mockRowHeader.fetchRow()).thenReturn(synch);

		// call under test
		source.removeItem(mockRowHeader);

		verify(mockSourceHandler).removeRow(synch);
		verify(mockRowHeader).fetchRow();
		verifyNoMoreInteractionsWithAllMocks();
	}

	@Test
	public void testMatches() {
		String key = "123";
		ConValue c1 = new ConValue(ConType.STRING, "one");
		ConValue c2 = new ConValue(ConType.BOOLEAN, true);
		SynapseRow synRow = new SynapseRow().setRowId(123L).setVersionNumber(0L).setEtag("e1");
		RowSourceItem synch = new RowSourceItem(new TreeMap<>(Map.of("a", c1, "b", c2)), key, synRow);

		RowCopyItemImpl copyItem = new RowCopyItemImpl().setCells(
				List.of(new CellCopyItem().setName("a").setValue(c1), new CellCopyItem().setName("b").setValue(c2)))
				.setSynapseRow(synRow);

		when(mockRowHeader.getKey()).thenReturn(key);
		when(mockRowHeader.getHash()).thenReturn(synch.getHash());

		// call under test
		assertTrue(source.matches(copyItem, mockRowHeader));
	}

	@Test
	public void testMatchesWithNewEtag() {
		String key = "123";
		ConValue c1 = new ConValue(ConType.STRING, "one");
		ConValue c2 = new ConValue(ConType.BOOLEAN, true);
		SynapseRow synRow = new SynapseRow().setRowId(123L).setVersionNumber(0L).setEtag("e1");
		RowSourceItem synch = new RowSourceItem(new TreeMap<>(Map.of("a", c1, "b", c2)), key, synRow);

		RowCopyItemImpl copyItem = new RowCopyItemImpl()
				.setCells(List.of(new CellCopyItem().setName("a").setValue(c1),
						new CellCopyItem().setName("b").setValue(c2)))
				.setSynapseRow(new SynapseRow().setRowId(123L).setVersionNumber(0L).setEtag("e2"));

		when(mockRowHeader.getKey()).thenReturn(key);
		when(mockRowHeader.getHash()).thenReturn(synch.getHash());

		// call under test
		assertFalse(source.matches(copyItem, mockRowHeader));
	}

	@Test
	public void testAddItemConsume() {
		when(mockRowReader.consumeRow("a")).thenReturn(Optional.of(mockRowHeader));
		when(mockRowReader.consumeRow("b")).thenReturn(Optional.empty());

		// call under test
		assertEquals(Optional.of(mockRowHeader), source.consume("a"));
		assertEquals(Optional.empty(), source.consume("b"));
	}

	@Test
	public void testStreamRemaining() {
		List<RowSourceItemReference> input = List.of(Mockito.mock(RowSourceItemReference.class),
				Mockito.mock(RowSourceItemReference.class));
		when(mockRowReader.remainingRows()).thenReturn(input.iterator());

		// call under test
		List<RowSourceItemReference> refs = source.streamRemaining().collect(Collectors.toList());
		assertEquals(input, refs);
	}

	@Test
	public void testIsItemAdditionSupported() {
		when(mockSourceHandler.canAddRemoveRows()).thenReturn(false);

		// call under test
		assertFalse(source.isItemAdditionSupported());

		verify(mockSourceHandler).canAddRemoveRows();
		verifyNoMoreInteractionsWithAllMocks();
	}

	@Test
	public void testIsItemRemovalSupported() {
		when(mockSourceHandler.canAddRemoveRows()).thenReturn(false);

		// call under test
		assertFalse(source.isItemRemovalSupported());

		verify(mockSourceHandler).canAddRemoveRows();
		verifyNoMoreInteractionsWithAllMocks();
	}

	@Test
	public void testIsExcludedFromMatching() {
		RowCopyItemImpl copyItem = new RowCopyItemImpl().setSynapseRow(new SynapseRow().setRowId(1L));
		when(mockSourceHandler.isUnmatchableCopyRow(copyItem)).thenReturn(true);

		// call under test — freezing delegates to the source handler's keying rules.
		assertTrue(source.isExcludedFromMatching(copyItem));

		verify(mockSourceHandler).isUnmatchableCopyRow(copyItem);
		verifyNoMoreInteractionsWithAllMocks();
	}

	@Test
	public void testWasDeletedByUserWhenInBaselineAndUnchanged() {
		when(mockRowHeader.getKey()).thenReturn("k1");
		when(mockSourceHandler.wasInSyncedBaseline("k1")).thenReturn(true);
		when(mockSourceHandler.changedSinceBaseline("k1")).thenReturn(false);

		// call under test — a row absent from the grid was deleted by the user iff its
		// key was in the synced baseline AND the source row has not changed since then.
		assertTrue(source.wasDeletedByUser(mockRowHeader));
	}

	@Test
	public void testWasDeletedByUserWhenNotInBaseline() {
		when(mockRowHeader.getKey()).thenReturn("k1");
		when(mockSourceHandler.wasInSyncedBaseline("k1")).thenReturn(false);

		// call under test — the key was never in the baseline, so its absence is a
		// source-side addition, not a user deletion.
		assertFalse(source.wasDeletedByUser(mockRowHeader));
	}

	@Test
	public void testWasDeletedByUserWhenSourceChangedSinceBaseline() {
		when(mockRowHeader.getKey()).thenReturn("k1");
		when(mockSourceHandler.wasInSyncedBaseline("k1")).thenReturn(true);
		when(mockSourceHandler.changedSinceBaseline("k1")).thenReturn(true);

		// call under test — the user deleted this row, but the source row changed since
		// the synced revision, so it is re-imported rather than treated as a deletion.
		assertFalse(source.wasDeletedByUser(mockRowHeader));
	}
}
