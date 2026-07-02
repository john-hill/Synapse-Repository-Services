package org.sagebionetworks.repo.manager.grid.synch.row;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.SynapseRow;
import org.sagebionetworks.repo.manager.grid.synch.handler.SourceHandler;
import org.sagebionetworks.repo.manager.grid.synch.io.RowSourceItem;
import org.sagebionetworks.repo.manager.grid.synch.io.RowSourceItemReference;
import org.sagebionetworks.repo.model.grid.patch.ConType;
import org.sagebionetworks.repo.model.grid.patch.ConValue;

@ExtendWith(MockitoExtension.class)
public class RowSyncOutcomeListenerTest {

	@Mock
	private SourceHandler mockSourceHandler;
	@Mock
	private RowSourceItemReference mockRowHeader;

	@InjectMocks
	private RowSyncOutcomeListener listener;

	@Test
	public void testOnRetainedInCopy() {
		ConValue a = new ConValue(ConType.STRING, "x");
		ConValue b = new ConValue(ConType.STRING, "y");
		RowCopyItemImpl copyItem = new RowCopyItemImpl().setCells(
				List.of(new CellCopyItem().setName("a").setValue(a), new CellCopyItem().setName("b").setValue(b)));

		// call under test — a retained row still survives and is forwarded to the handler.
		listener.onRetainedInCopy(copyItem);

		verify(mockSourceHandler).onSurvivingRow(Map.of("a", a, "b", b));
		verifyNoMoreInteractions(mockRowHeader);
	}

	@Test
	public void testOnPulledFromSourceToCopy() {
		ConValue a = new ConValue(ConType.LONG, 222L);
		TreeMap<String, ConValue> data = new TreeMap<>(Map.of("a", a));
		when(mockRowHeader.fetchRow())
				.thenReturn(new RowSourceItem(data, "syn123", new SynapseRow().setRowId(123L)));

		// call under test — a pulled-in source row survives; its fetched cells are forwarded.
		listener.onPulledFromSourceToCopy(mockRowHeader);

		verify(mockSourceHandler).onSurvivingRow(data);
		verify(mockRowHeader).fetchRow();
	}

}
