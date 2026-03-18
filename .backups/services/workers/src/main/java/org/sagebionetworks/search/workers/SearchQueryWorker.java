package org.sagebionetworks.search.workers;

import org.sagebionetworks.repo.manager.search.SearchIndexQueryManager;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dao.asynch.AsyncJobProgressCallback;
import org.sagebionetworks.repo.model.table.search.SearchQuery;
import org.sagebionetworks.repo.model.table.search.SearchResults;
import org.sagebionetworks.worker.AsyncJobRunner;
import org.sagebionetworks.workers.util.aws.message.RecoverableMessageException;
import org.springframework.stereotype.Service;

/**
 * Async job worker that executes search queries against a SearchIndex's OpenSearch index.
 * Delegates to SearchIndexQueryManager for authorization, configuration resolution, and query execution.
 * If the index is still building (CREATING), throws RecoverableMessageException for auto-retry.
 */
@Service
public class SearchQueryWorker implements AsyncJobRunner<SearchQuery, SearchResults> {

	private final SearchIndexQueryManager searchIndexQueryManager;

	public SearchQueryWorker(SearchIndexQueryManager searchIndexQueryManager) {
		this.searchIndexQueryManager = searchIndexQueryManager;
	}

	@Override
	public Class<SearchQuery> getRequestType() {
		return SearchQuery.class;
	}

	@Override
	public Class<SearchResults> getResponseType() {
		return SearchResults.class;
	}

	@Override
	public SearchResults run(String jobId, UserInfo user, SearchQuery request,
			AsyncJobProgressCallback jobProgressCallback)
			throws RecoverableMessageException, Exception {
		try {
			return searchIndexQueryManager.search(user, request);
		} catch (IllegalStateException e) {
			// If the index is still building, convert to RecoverableMessageException for auto-retry
			if (e.getMessage() != null && e.getMessage().contains("still building")) {
				throw new RecoverableMessageException(e.getMessage());
			}
			throw e;
		}
	}
}
