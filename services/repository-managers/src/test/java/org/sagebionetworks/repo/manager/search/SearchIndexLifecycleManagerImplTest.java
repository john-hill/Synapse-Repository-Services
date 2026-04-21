package org.sagebionetworks.repo.manager.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.manager.EntityManager;
import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.manager.table.TableManagerSupport;
import org.sagebionetworks.repo.manager.table.TableQueryManager;
import org.sagebionetworks.repo.model.dbo.search.ColumnAnalyzerOverrideDao;
import org.sagebionetworks.repo.model.dbo.search.SynonymSetDao;
import org.sagebionetworks.repo.model.dbo.search.TextAnalyzerDao;
import org.sagebionetworks.repo.model.entity.IdAndVersion;
import org.sagebionetworks.repo.model.table.TableState;
import org.sagebionetworks.repo.model.table.TableStatus;
import org.sagebionetworks.table.cluster.ConnectionFactory;
import org.sagebionetworks.workers.util.aws.message.RecoverableMessageException;

@ExtendWith(MockitoExtension.class)
public class SearchIndexLifecycleManagerImplTest {

	@Mock
	private ConnectionFactory connectionFactory;
	@Mock
	private OpenSearchManager openSearchManager;
	@Mock
	private SearchConfigurationResolver searchConfigurationResolver;
	@Mock
	private TableManagerSupport tableManagerSupport;
	@Mock
	private TableQueryManager tableQueryManager;
	@Mock
	private UserManager userManager;
	@Mock
	private EntityManager entityManager;
	@Mock
	private SynonymSetDao synonymSetDao;
	@Mock
	private ColumnAnalyzerOverrideDao columnAnalyzerOverrideDao;
	@Mock
	private TextAnalyzerDao textAnalyzerDao;

	@InjectMocks
	private SearchIndexLifecycleManagerImpl manager;

	@Test
	public void testCheckSourceTablesReadyWithAllAvailable() throws RecoverableMessageException {
		when(tableManagerSupport.getTableStatusOrCreateIfNotExists(any()))
			.thenReturn(new TableStatus().setState(TableState.AVAILABLE));

		// call under test
		manager.checkSourceTablesReady("SELECT * FROM syn123");
	}

	@Test
	public void testCheckSourceTablesReadyWithMultipleSourcesAllAvailable() throws RecoverableMessageException {
		when(tableManagerSupport.getTableStatusOrCreateIfNotExists(IdAndVersion.parse("syn123")))
			.thenReturn(new TableStatus().setState(TableState.AVAILABLE));
		when(tableManagerSupport.getTableStatusOrCreateIfNotExists(IdAndVersion.parse("syn456")))
			.thenReturn(new TableStatus().setState(TableState.AVAILABLE));

		// call under test
		manager.checkSourceTablesReady("SELECT a.x, b.y FROM syn123 a JOIN syn456 b ON a.id = b.id");
	}

	@Test
	public void testCheckSourceTablesReadyWithProcessingSource() {
		when(tableManagerSupport.getTableStatusOrCreateIfNotExists(IdAndVersion.parse("syn123")))
			.thenReturn(new TableStatus().setState(TableState.PROCESSING));

		// call under test
		RecoverableMessageException ex = assertThrows(RecoverableMessageException.class,
			() -> manager.checkSourceTablesReady("SELECT * FROM syn123"));

		assertEquals("One or more source tables are still processing. Deferring search index build.",
			ex.getMessage());
	}

	@Test
	public void testCheckSourceTablesReadyWithProcessingFailedSource() {
		when(tableManagerSupport.getTableStatusOrCreateIfNotExists(IdAndVersion.parse("syn123")))
			.thenReturn(new TableStatus().setState(TableState.PROCESSING_FAILED));

		// call under test
		IllegalStateException ex = assertThrows(IllegalStateException.class,
			() -> manager.checkSourceTablesReady("SELECT * FROM syn123"));

		assertEquals("Cannot build search index: source entity syn123 is in PROCESSING_FAILED state.",
			ex.getMessage());
	}

	@Test
	public void testCheckSourceTablesReadyWithProcessingFailedTakesPrecedenceOverProcessing() {
		// First source is processing, second is failed — failed should take precedence
		when(tableManagerSupport.getTableStatusOrCreateIfNotExists(IdAndVersion.parse("syn123")))
			.thenReturn(new TableStatus().setState(TableState.PROCESSING));
		when(tableManagerSupport.getTableStatusOrCreateIfNotExists(IdAndVersion.parse("syn456")))
			.thenReturn(new TableStatus().setState(TableState.PROCESSING_FAILED));

		// call under test
		IllegalStateException ex = assertThrows(IllegalStateException.class,
			() -> manager.checkSourceTablesReady("SELECT a.x, b.y FROM syn123 a JOIN syn456 b ON a.id = b.id"));

		assertEquals("Cannot build search index: source entity syn456 is in PROCESSING_FAILED state.",
			ex.getMessage());
	}

	@Test
	public void testCheckSourceTablesReadyWithNoSourceTables() {
		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
			() -> manager.checkSourceTablesReady("this is not valid sql"));

		// TableQueryParser throws IllegalArgumentException for unparseable SQL
	}

	@Test
	public void testCheckSourceTablesReadyWithVersionedSource() throws RecoverableMessageException {
		when(tableManagerSupport.getTableStatusOrCreateIfNotExists(IdAndVersion.parse("syn123.5")))
			.thenReturn(new TableStatus().setState(TableState.AVAILABLE));

		// call under test
		manager.checkSourceTablesReady("SELECT * FROM syn123.5");
	}
}
