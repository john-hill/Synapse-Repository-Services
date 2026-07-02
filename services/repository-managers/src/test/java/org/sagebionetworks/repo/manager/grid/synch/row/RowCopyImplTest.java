package org.sagebionetworks.repo.manager.grid.synch.row;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
	private IntendedChangePublisher mockPublisher;

	private final LogicalTimestamp rowsArrayId = new LogicalTimestamp().setReplicaId(1L).setSequenceNumber(2L);
	private final LogicalTimestamp lastRowId = new LogicalTimestamp().setReplicaId(3L).setSequenceNumber(4L);

	private final List<Column> finalSchema = List.of(new Column().setName("a").setVectorIndex(0),
			new Column().setName("b").setVectorIndex(1));

	RowCopyImpl setupCopy() {
		when(mockCopyHandler.getHeader()).thenReturn(mockGridHeader);
		when(mockGridHeader.getRowsId()).thenReturn(rowsArrayId);
		when(mockCopyHandler.getLastRowsRgaNodeId()).thenReturn(lastRowId);
		return new RowCopyImpl(finalSchema, mockPublisher, mockCopyHandler);
	}

	@Test
	public void testStreamItems() {
		List<RowCopyItem> rows = List.of(
				new RowCopyItemImpl().setSynapseRow(new SynapseRow().setRowId(111L)),
				new RowCopyItemImpl().setSynapseRow(new SynapseRow().setRowId(222L)));
		when(mockCopyHandler.getRows()).thenReturn(rows.iterator());
		RowCopyImpl copy = setupCopy();

		// call under test
		List<RowCopyItem> results = copy.streamItems().collect(Collectors.toList());

		assertEquals(rows, results);
		verifyNoMoreInteractions(mockPublisher);
	}

	@Test
	public void testRemoveItem() {
		RowCopyImpl copy = setupCopy();
		LogicalTimestamp rgaNodeId = new LogicalTimestamp().setReplicaId(5L).setSequenceNumber(6L);
		RowCopyItemImpl item = new RowCopyItemImpl().setRgaNodeId(rgaNodeId);

		// call under test — a row deleted from the source is removed from the grid.
		copy.removeItem(item);

		verify(mockPublisher).publish(new DeleteArrayNodeChange(rowsArrayId, rgaNodeId));
		verifyNoMoreInteractions(mockPublisher);
	}

	@Test
	public void testAddItem() {
		RowCopyImpl copy = setupCopy();
		SynapseRow synRow = new SynapseRow().setRowId(123L).setVersionNumber(0L).setEtag("e1");
		ConValue firstCellValue = new ConValue(ConType.LONG, 222L);
		ConValue secondCellValue = new ConValue(ConType.STRING, "other");
		TreeMap<String, ConValue> data = new TreeMap<>(Map.of("a", firstCellValue, "b", secondCellValue));
		when(mockRowHeader.fetchRow()).thenReturn(new RowSourceItem(data, "syn123", synRow));

		// call under test — a pulled-in source row is inserted into the grid
		copy.addItem(mockRowHeader);

		verify(mockPublisher).publish(new InsertRowChange(rowsArrayId, lastRowId,
				List.of(firstCellValue, secondCellValue), new Integer[] { 0, 1 }, synRow.toConValue()));
		verifyNoMoreInteractions(mockPublisher);
	}

}
