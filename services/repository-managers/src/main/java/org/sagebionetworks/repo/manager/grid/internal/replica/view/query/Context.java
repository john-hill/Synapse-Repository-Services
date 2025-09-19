package org.sagebionetworks.repo.manager.grid.internal.replica.view.query;

import org.sagebionetworks.repo.manager.grid.internal.replica.model.GridHeader;

public class Context {

	private final GridHeader header;

	public Context(GridHeader header) {
		super();
		this.header = header;
	}

	public GridHeader getHeader() {
		return header;
	}
	

}
