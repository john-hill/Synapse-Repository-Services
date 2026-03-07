package org.sagebionetworks.repo.manager.search;

import org.sagebionetworks.repo.model.table.search.ListTextAnalyzersRequest;
import org.sagebionetworks.repo.model.table.search.ListTextAnalyzersResponse;
import org.sagebionetworks.repo.model.table.search.TextAnalyzer;

public interface TextAnalyzerManager {

	TextAnalyzer get(Long id);

	ListTextAnalyzersResponse list(ListTextAnalyzersRequest request);
}
