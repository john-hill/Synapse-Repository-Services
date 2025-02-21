package org.sagebionetworks.change.workers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.StackConfiguration;
import org.sagebionetworks.cloudwatch.ProfileData;
import org.sagebionetworks.cloudwatch.WorkerLogger;
import org.sagebionetworks.repo.manager.message.RepositoryMessagePublisher;
import org.sagebionetworks.repo.model.ObjectType;
import org.sagebionetworks.repo.model.StackStatusDao;
import org.sagebionetworks.repo.model.dbo.dao.DBOChangeDAO;
import org.sagebionetworks.repo.model.message.ChangeMessage;
import org.sagebionetworks.util.TestClock;
import org.sagebionetworks.util.progress.ProgressCallback;

@ExtendWith(MockitoExtension.class)
public class ChangeSentMessageSynchWorkerUnitTest {

	@Mock
	private DBOChangeDAO mockChangeDao;
	@Mock
	private RepositoryMessagePublisher mockRepositoryMessagePublisher;
	@Mock
	private StackStatusDao mockStatusDao;
	@InjectMocks
	private ChangeSentMessageSynchWorker worker;
	@Mock
	private ProgressCallback mockCallback;
	@Mock
	private StackConfiguration mockConfiguration;
	@Mock
	private WorkerLogger mockLogger;
	@Mock
	private Random mockRandom;
	private int pageSize = 10;
	@Mock
	private TestClock mockClock;
	private ChangeMessage one;
	private ChangeMessage two;

	@BeforeEach
	public void before() {
		one = new ChangeMessage();
		one.setObjectType(ObjectType.ENTITY);
		one.setObjectId("one");
		two = new ChangeMessage();
		two.setObjectType(ObjectType.FILE);
		two.setObjectId("two");
	}

	@Test
	public void testStackNotReadWrite() throws Exception {
		when(mockStatusDao.isStackReadWrite()).thenReturn(false);
		worker.run(mockCallback);
		verify(mockChangeDao, never()).getMinimumChangeNumber();
		verify(mockChangeDao, never()).listUnsentMessages(anyLong(), anyLong(), any(Timestamp.class));
	}

	@Test
	public void testHappy() throws Exception {
		when(mockStatusDao.isStackReadWrite()).thenReturn(true);
		when(mockConfiguration.getChangeSynchWorkerMinPageSize()).thenReturn(pageSize);
		when(mockConfiguration.getChangeSynchWorkerSleepTimeMS()).thenReturn(1000L);
		when(mockRandom.nextInt(anyInt())).thenReturn(1);
		when(mockChangeDao.listUnsentMessages(anyLong(), anyLong(), any(Timestamp.class)))
				.thenReturn(Arrays.asList(one, two));

		long max = pageSize * 2 + 3;
		long min = 1;
		when(mockChangeDao.getCurrentChangeNumber()).thenReturn(max);
		when(mockChangeDao.getMinimumChangeNumber()).thenReturn(min);
		when(mockChangeDao.checkUnsentMessageByCheckSumForRange(1L, 11L)).thenReturn(true);
		when(mockChangeDao.checkUnsentMessageByCheckSumForRange(12L, 22L)).thenReturn(true);
		when(mockChangeDao.checkUnsentMessageByCheckSumForRange(23L, 33L)).thenReturn(false);
		// run
		worker.run(mockCallback);
		verify(mockRepositoryMessagePublisher).publishBatchToTopic(ObjectType.ENTITY, Arrays.asList(one));
		verify(mockRepositoryMessagePublisher).publishBatchToTopic(ObjectType.FILE, Arrays.asList(two));
		verify(mockLogger, times(10)).logCustomMetric(any(ProfileData.class));
		verify(mockClock, times(1)).currentTimeMillis();
		verify(mockClock, times(3)).sleepNoInterrupt(1000L);
	}

}
