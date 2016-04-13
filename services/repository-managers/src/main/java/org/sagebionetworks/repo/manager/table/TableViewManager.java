package org.sagebionetworks.repo.manager.table;

import org.sagebionetworks.repo.model.table.FileView;

/**
 * Abstraction for managing tables views such as FileViews.
 *
 */
public interface TableViewManager {
	
	/**
	 * Define the scope and schema for a FileView.
	 * 
	 * @param toUpdate
	 */
	public void setViewScopeAndSchema(FileView toUpdate);

}
