package org.sagebionetworks.repo.manager.grid.internal.replica.export;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.json.JSONArray;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.manager.grid.GridManager;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.Column;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.GridHeader;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.RowData;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.RowMetadata;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.RowObject;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.RowView;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.SynapseRow;
import org.sagebionetworks.repo.manager.grid.internal.replica.view.GridReplicaViewManager;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.grid.DownloadFromGridRequest;
import org.sagebionetworks.repo.model.grid.DownloadFromGridResult;
import org.sagebionetworks.repo.model.grid.GridConnectionInfo;
import org.sagebionetworks.repo.model.grid.GridSession;
import org.sagebionetworks.repo.web.TemporarilyUnavailableException;
import org.sagebionetworks.util.csv.CSVWriterStream;

@ExtendWith(MockitoExtension.class)
public class GridReplicaCsvExporterImplTest {
    @Mock
    private GridManager mockGridManager;
    @Mock
    private GridReplicaViewManager mockGridReplicaViewManager;
    @InjectMocks
    private GridReplicaCsvExporterImpl exporter;

    @Mock
    private UserInfo mockUserInfo;
    @Mock
    private GridSession mockGridSession;
    @Mock
    private GridConnectionInfo mockGridConnectionInfo;
    @Mock
    private GridHeader mockGridHeader;
    @Mock
    private CSVWriterStream mockCsvWriterStream;

    private DownloadFromGridRequest request;
    private List<RowView> rowViewPage;

    @BeforeEach
    public void before() {
        request = new DownloadFromGridRequest();
        request.setSessionId("some-session-id");

        rowViewPage = new ArrayList<>();
        rowViewPage.add(new RowView().setRowObject(new RowObject()
                .setMetadata(new RowMetadata().setSynapseRow(new SynapseRow().setRowId(1L).setVersionNumber(1L).setEtag("etag1")))
                .setData(new RowData().setCells(new JSONArray(List.of("a", "b"))))));
        rowViewPage.add(new RowView().setRowObject(new RowObject()
                .setMetadata(new RowMetadata().setSynapseRow(new SynapseRow().setRowId(2L).setVersionNumber(2L).setEtag("etag2")))
                .setData(new RowData().setCells(new JSONArray(List.of("c", "d"))))));
    }

    @Test
    public void testExportGridAsCsv() throws IOException {
        when(mockGridManager.getGridSession(mockUserInfo, request.getSessionId())).thenReturn(mockGridSession);
        when(mockGridManager.getDefaultInternalConnection(request.getSessionId())).thenReturn(Optional.of(mockGridConnectionInfo));
        when(mockGridConnectionInfo.getSessionId()).thenReturn(request.getSessionId());
        when(mockGridConnectionInfo.getReplicaId()).thenReturn(1L);
        when(mockGridReplicaViewManager.readHeader(request.getSessionId(), 1L)).thenReturn(Optional.of(mockGridHeader));
        when(mockGridHeader.getOrderedColumns()).thenReturn(List.of(
                new Column().setName("col1"),
                new Column().setName("col2")
        ));
        when(mockGridReplicaViewManager.querySinglePage(eq(mockGridHeader), any(), anyLong(), anyLong()))
                .thenReturn(rowViewPage)
                .thenReturn(Collections.emptyList());

        // Call under test
        DownloadFromGridResult result = exporter.exportGridAsCsv(mockUserInfo, request, mockCsvWriterStream);

        assertEquals(request.getSessionId(), result.getSessionId());
        ArgumentCaptor<String[]> captor = ArgumentCaptor.forClass(String[].class);
        verify(mockCsvWriterStream, times(3)).writeNext(captor.capture());
        List<String[]> writtenRows = captor.getAllValues();
        assertEquals(3, writtenRows.size());
        assertArrayEquals(new String[]{"ROW_ID", "ROW_VERSION", "etag", "col1", "col2"}, writtenRows.get(0));
        assertArrayEquals(new String[]{"1", "1", "etag1", "a", "b"}, writtenRows.get(1));
        assertArrayEquals(new String[]{"2", "2", "etag2", "c", "d"}, writtenRows.get(2));

        verify(mockGridManager).getGridSession(mockUserInfo, request.getSessionId());
        verify(mockGridManager).getDefaultInternalConnection(request.getSessionId());
        verify(mockGridReplicaViewManager).readHeader(request.getSessionId(), 1L);
        verify(mockGridReplicaViewManager).querySinglePage(eq(mockGridHeader), any(), eq(1000L), eq(0L));
    }

    @Test
    public void testExportGridAsCsvWithNoHeader() throws IOException {
        request.setWriteHeader(false);

        when(mockGridManager.getGridSession(mockUserInfo, request.getSessionId())).thenReturn(mockGridSession);
        when(mockGridManager.getDefaultInternalConnection(request.getSessionId())).thenReturn(Optional.of(mockGridConnectionInfo));
        when(mockGridConnectionInfo.getSessionId()).thenReturn(request.getSessionId());
        when(mockGridConnectionInfo.getReplicaId()).thenReturn(1L);
        when(mockGridReplicaViewManager.readHeader(request.getSessionId(), 1L)).thenReturn(Optional.of(mockGridHeader));
        when(mockGridReplicaViewManager.querySinglePage(eq(mockGridHeader), any(), anyLong(), anyLong()))
                .thenReturn(rowViewPage)
                .thenReturn(Collections.emptyList());

        // Call under test
        exporter.exportGridAsCsv(mockUserInfo, request, mockCsvWriterStream);

        ArgumentCaptor<String[]> captor = ArgumentCaptor.forClass(String[].class);
        verify(mockCsvWriterStream, times(2)).writeNext(captor.capture());
        List<String[]> writtenRows = captor.getAllValues();
        assertEquals(2, writtenRows.size());
        assertArrayEquals(new String[]{"1", "1", "etag1", "a", "b"}, writtenRows.get(0));
        assertArrayEquals(new String[]{"2", "2", "etag2", "c", "d"}, writtenRows.get(1));
    }

    @Test
    public void testExportGridAsCsvWithoutRowIdAndVersion() throws IOException {
        request.setIncludeRowIdAndRowVersion(false);

        when(mockGridManager.getGridSession(mockUserInfo, request.getSessionId())).thenReturn(mockGridSession);
        when(mockGridManager.getDefaultInternalConnection(request.getSessionId())).thenReturn(Optional.of(mockGridConnectionInfo));
        when(mockGridConnectionInfo.getSessionId()).thenReturn(request.getSessionId());
        when(mockGridConnectionInfo.getReplicaId()).thenReturn(1L);
        when(mockGridReplicaViewManager.readHeader(request.getSessionId(), 1L)).thenReturn(Optional.of(mockGridHeader));
        when(mockGridHeader.getOrderedColumns()).thenReturn(List.of(
                new Column().setName("col1"),
                new Column().setName("col2")
        ));
        when(mockGridReplicaViewManager.querySinglePage(eq(mockGridHeader), any(), anyLong(), anyLong()))
                .thenReturn(rowViewPage)
                .thenReturn(Collections.emptyList());

        // Call under test
        exporter.exportGridAsCsv(mockUserInfo, request, mockCsvWriterStream);

        ArgumentCaptor<String[]> captor = ArgumentCaptor.forClass(String[].class);
        verify(mockCsvWriterStream, times(3)).writeNext(captor.capture());
        List<String[]> writtenRows = captor.getAllValues();
        assertEquals(3, writtenRows.size());
        assertArrayEquals(new String[]{"etag", "col1", "col2"}, writtenRows.get(0));
        assertArrayEquals(new String[]{"etag1", "a", "b"}, writtenRows.get(1));
        assertArrayEquals(new String[]{"etag2", "c", "d"}, writtenRows.get(2));
    }

    @Test
    public void testExportGridAsCsvWithoutEtag() throws IOException {
        request.setIncludeEtag(false);

        when(mockGridManager.getGridSession(mockUserInfo, request.getSessionId())).thenReturn(mockGridSession);
        when(mockGridManager.getDefaultInternalConnection(request.getSessionId())).thenReturn(Optional.of(mockGridConnectionInfo));
        when(mockGridConnectionInfo.getSessionId()).thenReturn(request.getSessionId());
        when(mockGridConnectionInfo.getReplicaId()).thenReturn(1L);
        when(mockGridReplicaViewManager.readHeader(request.getSessionId(), 1L)).thenReturn(Optional.of(mockGridHeader));
        when(mockGridHeader.getOrderedColumns()).thenReturn(List.of(
                new Column().setName("col1"),
                new Column().setName("col2")
        ));
        when(mockGridReplicaViewManager.querySinglePage(eq(mockGridHeader), any(), anyLong(), anyLong()))
                .thenReturn(rowViewPage)
                .thenReturn(Collections.emptyList());

        // Call under test
        exporter.exportGridAsCsv(mockUserInfo, request, mockCsvWriterStream);

        ArgumentCaptor<String[]> captor = ArgumentCaptor.forClass(String[].class);
        verify(mockCsvWriterStream, times(3)).writeNext(captor.capture());
        List<String[]> writtenRows = captor.getAllValues();
        assertEquals(3, writtenRows.size());
        assertArrayEquals(new String[]{"ROW_ID", "ROW_VERSION", "col1", "col2"}, writtenRows.get(0));
        assertArrayEquals(new String[]{"1", "1", "a", "b"}, writtenRows.get(1));
        assertArrayEquals(new String[]{"2", "2", "c", "d"}, writtenRows.get(2));
    }

    @Test
    public void testExportGridAsCsvWithNullValues() throws IOException {
        rowViewPage.get(0).getRowObject().getMetadata().getSynapseRow().setRowId(null).setVersionNumber(null).setEtag(null);
        rowViewPage.get(0).getRowObject().getData().setCells(new JSONArray("[\"a\",null]"));

        when(mockGridManager.getGridSession(mockUserInfo, request.getSessionId())).thenReturn(mockGridSession);
        when(mockGridManager.getDefaultInternalConnection(request.getSessionId())).thenReturn(Optional.of(mockGridConnectionInfo));
        when(mockGridConnectionInfo.getSessionId()).thenReturn(request.getSessionId());
        when(mockGridConnectionInfo.getReplicaId()).thenReturn(1L);
        when(mockGridReplicaViewManager.readHeader(request.getSessionId(), 1L)).thenReturn(Optional.of(mockGridHeader));
        when(mockGridHeader.getOrderedColumns()).thenReturn(List.of(
                new Column().setName("col1"),
                new Column().setName("col2")
        ));
        when(mockGridReplicaViewManager.querySinglePage(eq(mockGridHeader), any(), anyLong(), anyLong()))
                .thenReturn(rowViewPage)
                .thenReturn(Collections.emptyList());

        // Call under test
        exporter.exportGridAsCsv(mockUserInfo, request, mockCsvWriterStream);

        ArgumentCaptor<String[]> captor = ArgumentCaptor.forClass(String[].class);
        verify(mockCsvWriterStream, times(3)).writeNext(captor.capture());
        List<String[]> writtenRows = captor.getAllValues();
        assertEquals(3, writtenRows.size());
        assertArrayEquals(new String[]{"ROW_ID", "ROW_VERSION", "etag", "col1", "col2"}, writtenRows.get(0));
        assertArrayEquals(new String[]{"", "", "", "a", ""}, writtenRows.get(1));
        assertArrayEquals(new String[]{"2", "2", "etag2", "c", "d"}, writtenRows.get(2));
    }

    @Test
    public void testExportGridAsCsvWithNoConnection() {
        when(mockGridManager.getGridSession(mockUserInfo, request.getSessionId())).thenReturn(mockGridSession);
        when(mockGridManager.getDefaultInternalConnection(request.getSessionId())).thenReturn(Optional.empty());

        assertThrows(TemporarilyUnavailableException.class, () -> {
            exporter.exportGridAsCsv(mockUserInfo, request, mockCsvWriterStream);
        });
    }

    @Test
    public void testExportGridAsCsvWithNoHeaderFound() {
        when(mockGridManager.getGridSession(mockUserInfo, request.getSessionId())).thenReturn(mockGridSession);
        when(mockGridManager.getDefaultInternalConnection(request.getSessionId())).thenReturn(Optional.of(mockGridConnectionInfo));
        when(mockGridConnectionInfo.getSessionId()).thenReturn(request.getSessionId());
        when(mockGridConnectionInfo.getReplicaId()).thenReturn(1L);
        when(mockGridReplicaViewManager.readHeader(request.getSessionId(), 1L)).thenReturn(Optional.empty());

        assertThrows(TemporarilyUnavailableException.class, () -> {
            exporter.exportGridAsCsv(mockUserInfo, request, mockCsvWriterStream);
        });
    }
}
