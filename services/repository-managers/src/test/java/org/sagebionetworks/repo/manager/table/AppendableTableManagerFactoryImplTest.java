package org.sagebionetworks.repo.manager.table;

import static org.junit.Assert.*;

import java.util.HashMap;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import static org.mockito.Mockito.*;
import org.mockito.MockitoAnnotations;
import org.sagebionetworks.repo.model.EntityType;
import org.springframework.test.util.ReflectionTestUtils;

public class AppendableTableManagerFactoryImplTest {

	@Mock
	TableManagerSupport tableManagerSupport;
	@Mock
	AppendableTableManager mockAppendableTableManager;
	
	Map<EntityType, AppendableTableManager> typeToManager;
	
	AppendableTableManagerFactoryImpl manager;
	
	@Before
	public void before(){
		MockitoAnnotations.initMocks(this);
		manager = new AppendableTableManagerFactoryImpl();
		ReflectionTestUtils.setField(manager, "tableManagerSupport", tableManagerSupport);
		typeToManager = new HashMap<EntityType, AppendableTableManager>();
		typeToManager.put(EntityType.table, mockAppendableTableManager);
		manager.setTypeToManager(typeToManager);
	}
	
	@Test
	public void testGetManagerForType(){
		AppendableTableManager result = manager.getManagerForType(EntityType.table);
		assertEquals(mockAppendableTableManager, result);
	}
	
	@Test (expected=IllegalArgumentException.class)
	public void testGetManagerForTypeNull(){
		manager.getManagerForType(null);
	}
	
	@Test (expected=IllegalArgumentException.class)
	public void testGetManagerForTypeUnknownType(){
		AppendableTableManager result = manager.getManagerForType(EntityType.project);
		assertEquals(mockAppendableTableManager, result);
	}
	
	@Test
	public void testGetManagerForEntity(){
		String tableId = "syn123";
		when(tableManagerSupport.getTableEntityType(tableId)).thenReturn(EntityType.table);
		AppendableTableManager result = manager.getManagerForEntity(tableId);
		assertEquals(mockAppendableTableManager, result);
	}

}
