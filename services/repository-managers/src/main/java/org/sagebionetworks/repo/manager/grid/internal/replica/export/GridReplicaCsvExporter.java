package org.sagebionetworks.repo.manager.grid.internal.replica.export;

import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.grid.DownloadFromGridRequest;
import org.sagebionetworks.repo.model.grid.DownloadFromGridResult;
import org.sagebionetworks.util.csv.CSVWriterStream;

public interface GridReplicaCsvExporter {

	/**
	 * Exports the current state of the specified grid session to a CSV file.
	 *
	 * @param userInfo - the user who invoked the export
	 * @param request - request details about the grid session and CSV to export.
     * @param stream - the stream to write the CSV data to.
	 * @return - the FileHandle for the CSV.
	 */
    DownloadFromGridResult exportGridAsCsv(UserInfo userInfo, DownloadFromGridRequest request, CSVWriterStream stream);

}
