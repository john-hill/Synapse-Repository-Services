package org.sagebionetworks.grid.db;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class GridIndexManagerImplTest {
	@Mock
	private GridIndexDao mockDao;

	@Mock
	private OperationDispatcher mockOperationDispatcher;

	@InjectMocks
	private GridIndexManagerImpl manager;

	@BeforeEach
	public void before() {

	}

}
