package org.sagebionetworks.repo.service.search;

import java.util.Collections;

import org.sagebionetworks.repo.model.asynch.AsynchronousJobStatus;
import org.sagebionetworks.repo.model.table.search.SearchQuery;
import org.sagebionetworks.repo.model.table.search.SearchResults;
import org.sagebionetworks.repo.service.AsynchronousJobServices;
import org.springframework.stereotype.Service;

@Service
public class SearchIndexQueryServiceImpl implements SearchIndexQueryService {

	private final AsynchronousJobServices asynchJobServices;

	public SearchIndexQueryServiceImpl(AsynchronousJobServices asynchJobServices) {
		this.asynchJobServices = asynchJobServices;
	}

	@Override
	public AsynchronousJobStatus startSearchQuery(Long userId, SearchQuery query) {
		return asynchJobServices.startJob(userId, query);
	}

	@Override
	public AsynchronousJobStatus getSearchQueryResults(Long userId, String asyncToken) throws Throwable {
		return asynchJobServices.getJobStatusAndThrow(userId, asyncToken);
	}

	@Override
	public SearchResults autocomplete(Long userId, SearchQuery query) {
		SearchResults results = new SearchResults();
		results.setSearchIndexId(query.getSearchIndexId());
		results.setTotalHits(0L);
		results.setHits(Collections.emptyList());
		results.setFrom(0L);
		return results;
	}
}
