package org.sagebionetworks.repo.manager.table;

import org.sagebionetworks.repo.model.dao.table.ColumnModelDAO;
import org.sagebionetworks.repo.model.dbo.dao.table.ViewScopeDao;
import org.sagebionetworks.repo.model.table.FileView;
import org.springframework.beans.factory.annotation.Autowired;

public class TableViewManagerImpl implements TableViewManager {
	
	@Autowired
	ViewScopeDao viewScopeDao;
	@Autowired
	ColumnModelDAO columnModelDao;

	@Override
	public void setViewScopeAndSchema(FileView toUpdate) {
		// TODO Auto-generated method stub

	}

}
