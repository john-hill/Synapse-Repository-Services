package org.sagebionetworks.repo.manager.grid.synch;

import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.manager.grid.internal.replica.change.PatchBuilderPublisher;
import org.sagebionetworks.repo.model.EntityType;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dao.asynch.AsyncJobProgressCallback;
import org.sagebionetworks.repo.model.dbo.grid.GridSource;
import org.sagebionetworks.repo.model.grid.GridSession;

@ExtendWith(MockitoExtension.class)
public class GridSynchronizationManagerImplTest {

	@Mock
	private PatchBuilderPublisher mockPatchBuilderPublisher;
	@Mock
	private SourceHandlerProvdier mockSourceHandlerProvdier;
	@Mock
	private CopyReaderProvider mockCopyReaderProvider;

	@InjectMocks
	private GridSynchronizationManagerImpl manager;

	@Mock
	private AsyncJobProgressCallback mockCallback;
	@Mock
	private UserInfo mockUser;

	private GridSession session;
	private GridSource gridSource;

	@BeforeEach
	public void before() {
		session = new GridSession().setSessionId("123");
		gridSource = new GridSource(444L, EntityType.entityview);

	}

	@Test
	public void test() {

		TestStubSourceHandler sourceHandler = new TestStubSourceHandler();
		

		when(mockSourceHandlerProvdier.createNewProvider(mockCallback, mockUser, session, gridSource))
				.thenReturn(sourceHandler);

	}
}
