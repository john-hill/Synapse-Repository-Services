package org.sagebionetworks.repo.model.dbo.search;

import java.util.List;
import java.util.Optional;

import org.sagebionetworks.repo.model.table.search.TextAnalyzer;

public interface TextAnalyzerDao {

	TextAnalyzer create(TextAnalyzer analyzer, Long userId);

	Optional<TextAnalyzer> get(Long id);

	TextAnalyzer update(TextAnalyzer analyzer, Long userId);

	void delete(Long id);

	List<TextAnalyzer> listByOrganization(Long organizationId, long limit, long offset);

	List<TextAnalyzer> listSystem();

	boolean exists(Long id);

	void createOrUpdateSystemAnalyzer(Long id, TextAnalyzer analyzer, Long userId);

	void truncateAll();
}
