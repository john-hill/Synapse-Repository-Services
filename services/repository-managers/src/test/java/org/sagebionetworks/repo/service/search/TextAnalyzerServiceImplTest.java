package org.sagebionetworks.repo.service.search;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.manager.search.TextAnalyzerManager;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.table.search.ListTextAnalyzersRequest;
import org.sagebionetworks.repo.model.table.search.ListTextAnalyzersResponse;
import org.sagebionetworks.repo.model.table.search.TextAnalyzer;

@ExtendWith(MockitoExtension.class)
public class TextAnalyzerServiceImplTest {

	@Mock
	private UserManager userManager;
	@Mock
	private TextAnalyzerManager textAnalyzerManager;

	private TextAnalyzerServiceImpl service;
	private UserInfo userInfo;

	@BeforeEach
	void setUp() {
		service = new TextAnalyzerServiceImpl(userManager, textAnalyzerManager);
		userInfo = new UserInfo(false);
		userInfo.setId(1L);
		when(userManager.getUserInfo(1L)).thenReturn(userInfo);
	}

	@Test
	public void testCreateDelegates() {
		TextAnalyzer input = new TextAnalyzer().setName("test");
		TextAnalyzer expected = new TextAnalyzer().setId("1000").setName("test");
		when(textAnalyzerManager.create(userInfo, input)).thenReturn(expected);

		TextAnalyzer result = service.create(1L, input);
		assertEquals("1000", result.getId());
		verify(textAnalyzerManager).create(userInfo, input);
	}

	@Test
	public void testGetDelegates() {
		TextAnalyzer expected = new TextAnalyzer().setId("1");
		when(textAnalyzerManager.get(userInfo, 1L)).thenReturn(expected);

		TextAnalyzer result = service.get(1L, 1L);
		assertEquals("1", result.getId());
		verify(textAnalyzerManager).get(userInfo, 1L);
	}

	@Test
	public void testUpdateDelegates() {
		TextAnalyzer input = new TextAnalyzer().setId("1").setName("updated");
		when(textAnalyzerManager.update(userInfo, input)).thenReturn(input);

		TextAnalyzer result = service.update(1L, input);
		assertEquals("updated", result.getName());
		verify(textAnalyzerManager).update(userInfo, input);
	}

	@Test
	public void testDeleteDelegates() {
		service.delete(1L, 1L);
		verify(textAnalyzerManager).delete(userInfo, 1L);
	}

	@Test
	public void testListDelegates() {
		ListTextAnalyzersRequest request = new ListTextAnalyzersRequest();
		ListTextAnalyzersResponse expected = new ListTextAnalyzersResponse();
		when(textAnalyzerManager.list(userInfo, request)).thenReturn(expected);

		ListTextAnalyzersResponse result = service.list(1L, request);
		assertSame(expected, result);
		verify(textAnalyzerManager).list(userInfo, request);
	}
}
