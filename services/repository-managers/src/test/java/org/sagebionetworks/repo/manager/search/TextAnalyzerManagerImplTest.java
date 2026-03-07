package org.sagebionetworks.repo.manager.search;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.model.dbo.search.TextAnalyzerDao;
import org.sagebionetworks.repo.model.table.search.ListTextAnalyzersRequest;
import org.sagebionetworks.repo.model.table.search.ListTextAnalyzersResponse;
import org.sagebionetworks.repo.model.table.search.TextAnalyzer;
import org.sagebionetworks.repo.web.NotFoundException;

@ExtendWith(MockitoExtension.class)
public class TextAnalyzerManagerImplTest {

	@Mock
	private TextAnalyzerDao textAnalyzerDao;

	private TextAnalyzerManagerImpl manager;

	@BeforeEach
	void setUp() {
		manager = new TextAnalyzerManagerImpl(textAnalyzerDao);
	}

	@Test
	public void testGetExisting() {
		TextAnalyzer analyzer = new TextAnalyzer();
		analyzer.setId("1");
		when(textAnalyzerDao.get(1L)).thenReturn(Optional.of(analyzer));

		assertEquals("1", manager.get(1L).getId());
	}

	@Test
	public void testGetNotFoundThrows() {
		when(textAnalyzerDao.get(999L)).thenReturn(Optional.empty());

		assertThrows(NotFoundException.class, () -> manager.get(999L));
	}

	@Test
	public void testListByOrganizationDelegatesToDao() {
		when(textAnalyzerDao.listByOrganization(42L, 51L, 0L)).thenReturn(Arrays.asList(new TextAnalyzer()));

		ListTextAnalyzersRequest request = new ListTextAnalyzersRequest();
		request.setOrganizationId("42");

		ListTextAnalyzersResponse response = manager.list(request);

		assertEquals(1, response.getResults().size());
		assertNull(response.getNextPageToken());
	}

	@Test
	public void testListByOrganizationPaginatesWhenMoreResults() {
		// Manager requests limit+1 (51) to detect if there are more pages.
		// When 51 results come back, it should return 50 and set nextPageToken.
		List<TextAnalyzer> fullPage = new ArrayList<>();
		for (int i = 0; i < 51; i++) {
			fullPage.add(new TextAnalyzer());
		}
		when(textAnalyzerDao.listByOrganization(42L, 51L, 0L)).thenReturn(fullPage);

		ListTextAnalyzersRequest request = new ListTextAnalyzersRequest();
		request.setOrganizationId("42");

		ListTextAnalyzersResponse response = manager.list(request);

		assertEquals(50, response.getResults().size());
		assertEquals("50", response.getNextPageToken());
	}

	@Test
	public void testListByOrganizationWithNextPageToken() {
		when(textAnalyzerDao.listByOrganization(42L, 51L, 50L)).thenReturn(Arrays.asList(new TextAnalyzer()));

		ListTextAnalyzersRequest request = new ListTextAnalyzersRequest();
		request.setOrganizationId("42");
		request.setNextPageToken("50");

		ListTextAnalyzersResponse response = manager.list(request);

		assertEquals(1, response.getResults().size());
		assertNull(response.getNextPageToken());
	}
}
