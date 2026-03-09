package org.sagebionetworks.repo.manager.search;

import java.util.List;

import org.sagebionetworks.repo.model.dbo.search.TextAnalyzerDao;
import org.sagebionetworks.repo.model.table.search.ListTextAnalyzersRequest;
import org.sagebionetworks.repo.model.table.search.ListTextAnalyzersResponse;
import org.sagebionetworks.repo.model.table.search.TextAnalyzer;
import org.sagebionetworks.repo.web.NotFoundException;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.stereotype.Service;

@Service
public class TextAnalyzerManagerImpl implements TextAnalyzerManager {

	private static final long DEFAULT_LIMIT = 50L;

	private final TextAnalyzerDao textAnalyzerDao;

	public TextAnalyzerManagerImpl(TextAnalyzerDao textAnalyzerDao) {
		this.textAnalyzerDao = textAnalyzerDao;
	}

	@Override
	public TextAnalyzer get(Long id) {
		ValidateArgument.required(id, "id");
		return textAnalyzerDao.get(id)
				.orElseThrow(() -> new NotFoundException("TextAnalyzer with id '" + id + "' does not exist."));
	}

	@Override
	public ListTextAnalyzersResponse list(ListTextAnalyzersRequest request) {
		ValidateArgument.required(request, "request");

		ListTextAnalyzersResponse response = new ListTextAnalyzersResponse();

		long offset = 0L;
		long limit = DEFAULT_LIMIT;
		if (request.getNextPageToken() != null) {
			offset = Long.parseLong(request.getNextPageToken());
		}
		List<TextAnalyzer> results;
		if (request.getOrganizationId() == null) {
			results = textAnalyzerDao.listAll(limit + 1, offset);
		} else {
			results = textAnalyzerDao.listByOrganization(
					Long.parseLong(request.getOrganizationId()), limit + 1, offset);
		}

		if (results.size() > limit) {
			results = results.subList(0, (int) limit);
			response.setNextPageToken(String.valueOf(offset + limit));
		}
		response.setResults(results);

		return response;
	}
}
