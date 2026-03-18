package org.sagebionetworks.search.workers;

import java.util.Collections;

import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dao.asynch.AsyncJobProgressCallback;
import org.sagebionetworks.repo.model.table.search.SearchQuery;
import org.sagebionetworks.repo.model.table.search.SearchResults;
import org.sagebionetworks.worker.AsyncJobRunner;
import org.springframework.stereotype.Service;

@Service
public class SearchQueryWorker implements AsyncJobRunner<SearchQuery, SearchResults> {

	@Override
	public Class<SearchQuery> getRequestType() {
		return SearchQuery.class;
	}

	@Override
	public Class<SearchResults> getResponseType() {
		return SearchResults.class;
	}

	@Override
	public SearchResults run(String jobId, UserInfo user, SearchQuery request, AsyncJobProgressCallback jobProgressCallback) {
		SearchResults results = new SearchResults();
		results.setSearchIndexId(request.getSearchIndexId());
		results.setTotalHits(0L);
		results.setHits(Collections.emptyList());
		results.setFrom(0L);
		return results;
	}
}
