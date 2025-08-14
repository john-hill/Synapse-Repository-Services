package org.sagebionetworks.repo.manager.grid.internal.replica.export;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.sagebionetworks.repo.manager.grid.GridManager;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.Column;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.GridHeader;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.RowView;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.SynapseRow;
import org.sagebionetworks.repo.manager.grid.internal.replica.view.GridReplicaViewManager;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.grid.DownloadFromGridRequest;
import org.sagebionetworks.repo.model.grid.DownloadFromGridResult;
import org.sagebionetworks.repo.model.grid.GridConnectionInfo;
import org.sagebionetworks.repo.model.table.TableConstants;
import org.sagebionetworks.util.PaginationIterator;
import org.sagebionetworks.util.ValidateArgument;
import org.sagebionetworks.util.csv.CSVWriterStream;
import org.sagebionetworks.workers.util.aws.message.RecoverableMessageException;
import org.springframework.stereotype.Service;

@Service
public class GridReplicaCsvExporterImpl implements GridReplicaCsvExporter {
    private final GridManager gridManager;
    private final GridReplicaViewManager gridReplicaViewManager;

    public GridReplicaCsvExporterImpl(
            GridManager gridManager,
            GridReplicaViewManager gridReplicaViewManager
    ) {
        this.gridManager = gridManager;
        this.gridReplicaViewManager = gridReplicaViewManager;
    }

    @Override
    public DownloadFromGridResult exportGridAsCsv(UserInfo userInfo, DownloadFromGridRequest request, CSVWriterStream stream) {
        ValidateArgument.required(userInfo, "userInfo");
        ValidateArgument.required(request.getSessionId(), "request.sessionId");

        // Get the grid session (and verify that the user has access to it)
        gridManager.getGridSession(userInfo, request.getSessionId());

        GridConnectionInfo connectionInfo = gridManager.getDefaultInternalConnection(request.getSessionId())
                .orElseThrow(() -> new RecoverableMessageException("No internal connection found for session: " + request.getSessionId()));
        GridHeader header = gridReplicaViewManager.readHeader(connectionInfo.getSessionId(), connectionInfo.getReplicaId())
                .orElseThrow(() -> new RecoverableMessageException("Grid header has not yet been instantiated for sessionId: " + request.getSessionId()));

        boolean writeHeader = request.getWriteHeader() != null ? request.getWriteHeader() : true;
        boolean includeRowIdAndRowVersion = request.getIncludeRowIdAndRowVersion() != null ? request.getIncludeRowIdAndRowVersion() : true;
        boolean includeEtag = request.getIncludeEtag() != null ? request.getIncludeEtag() : true;

        try {
            if (writeHeader) {
                List<String> csvHeader = new ArrayList<>();
                if (includeRowIdAndRowVersion) {
                    csvHeader.add(TableConstants.ROW_ID);
                    csvHeader.add(TableConstants.ROW_VERSION);
                }
                if (includeEtag) {
                    csvHeader.add("etag");
                }
                header.getOrderedColumns().stream().map(Column::getName).forEach(csvHeader::add);
                stream.writeNext(csvHeader.toArray(new String[0]));
            }

            // Note that the grid has a maximum of 100,000 rows
            final long ROWS_PER_PAGE = 1_000L;
            PaginationIterator<RowView> iterator = new PaginationIterator<>(
                    (long limit, long offset) -> gridReplicaViewManager.querySinglePage(header, Collections.emptyList(), limit, offset),
                    ROWS_PER_PAGE
            );

            while (iterator.hasNext()) {
                RowView rowView = iterator.next();
                List<String> csvRow = new ArrayList<>();
                SynapseRow synapseRow = rowView.getSynapseRow();

                if (includeRowIdAndRowVersion) {
                    Long rowId = synapseRow != null ? synapseRow.getRowId() : null;
                    csvRow.add(rowId == null ? "" : rowId.toString());

                    Long rowVersion = synapseRow != null ? synapseRow.getVersionNumber() : null;
                    csvRow.add(rowVersion == null ? "" : rowVersion.toString());
                }

                if (includeEtag) {
                    String etag = synapseRow != null ? synapseRow.getEtag() : null;
                    csvRow.add(etag == null ? "" : etag);
                }

                rowView.getCells().toList().stream()
                        .map(v -> v == null ? "" : v.toString())
                        .forEach(csvRow::add);

                stream.writeNext(csvRow.toArray(new String[0]));
            }
        } catch (IOException e) {
            throw new RuntimeException("Error writing to CSV stream", e);
        }
        DownloadFromGridResult result = new DownloadFromGridResult();
        result.setSessionId(request.getSessionId());
        // The file handle will be created and added to the response by the worker that calls this method.
        return result;
    }
}
