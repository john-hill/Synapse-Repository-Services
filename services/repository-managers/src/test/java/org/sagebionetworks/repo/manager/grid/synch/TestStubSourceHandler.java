package org.sagebionetworks.repo.manager.grid.synch;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.sagebionetworks.repo.model.grid.patch.ConValue;
import org.sagebionetworks.repo.model.table.ColumnModel;

public class TestStubSourceHandler implements SourceHandler {

	@Override
	public void close() throws Exception {
		// TODO Auto-generated method stub
		
	}

	@Override
	public RowReader getSourceRowReader() throws IOException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getRowKey(CopyRow rowView) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void addNewRowToSource(SynchRow copy) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public List<ColumnModel> getCurrentSourceSchema() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void addColumnToSource(String name) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void applyCellChangesFromCopyToSource(String rowId, Map<String, ConValue> changedCells) {
		// TODO Auto-generated method stub
		
	}

}
