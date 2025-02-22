package org.sagebionetworks.repo.manager.table;

import java.io.IOException;

import org.sagebionetworks.repo.manager.table.query.QueryTranslations;
import org.sagebionetworks.repo.model.dao.table.RowHandler;

@FunctionalInterface
public interface RowHandlerProvider {

	/**
	 * Get a {@link RowHandler} to stream over query results for the provided {@link QueryTranslations}
	 * @param trasnaltor
	 * @return
	 */
	RowHandler getHandler(QueryTranslations trasnaltor) throws IOException;

}
