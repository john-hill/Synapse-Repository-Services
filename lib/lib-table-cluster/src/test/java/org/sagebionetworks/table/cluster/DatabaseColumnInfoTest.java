package org.sagebionetworks.table.cluster;



import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.sagebionetworks.repo.model.table.ColumnConstants;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.repo.model.table.TableConstants;
import org.sagebionetworks.table.query.util.ColumnTypeListMappings;

public class DatabaseColumnInfoTest {
		
	@Test
	public void testCreateIndexDefinitionMediumText(){
		DatabaseColumnInfo info = new DatabaseColumnInfo();
		info.setColumnName("_C123_");
		info.setIndexName("_C123_IDX");
		info.setType(MySqlColumnType.MEDIUMTEXT);
		// call under test
		String results = info.createIndexDefinition();
		assertEquals("_C123_IDX (_C123_(255))", results);
	}
	
	@Test
	public void testCreateIndexDefinitionText(){
		DatabaseColumnInfo info = new DatabaseColumnInfo();
		info.setColumnName("_C123_");
		info.setIndexName("_C123_IDX");
		info.setType(MySqlColumnType.TEXT);
		// call under test
		String results = info.createIndexDefinition();
		assertEquals("_C123_IDX (_C123_(255))", results);
	}
	
	@Test
	public void testCreateIndexDefinitionVarcharUnderMax(){
		DatabaseColumnInfo info = new DatabaseColumnInfo();
		info.setColumnName("_C123_");
		info.setIndexName("_C123_IDX");
		info.setType(MySqlColumnType.VARCHAR);
		info.setMaxSize(ColumnConstants.MAX_MYSQL_VARCHAR_INDEX_LENGTH-1);
		// call under test
		String results = info.createIndexDefinition();
		assertEquals("_C123_IDX (_C123_)", results);
	}
	
	@Test
	public void testCreateIndexDefinitionVarcharAtMax(){
		DatabaseColumnInfo info = new DatabaseColumnInfo();
		info.setColumnName("_C123_");
		info.setIndexName("_C123_IDX");
		info.setType(MySqlColumnType.VARCHAR);
		info.setMaxSize(ColumnConstants.MAX_MYSQL_VARCHAR_INDEX_LENGTH);
		// call under test
		String results = info.createIndexDefinition();
		assertEquals("_C123_IDX (_C123_(255))", results);
	}
	
	@Test
	public void testCreateIndexDefinitionVarcharOverMax(){
		DatabaseColumnInfo info = new DatabaseColumnInfo();
		info.setColumnName("_C123_");
		info.setIndexName("_C123_IDX");
		info.setType(MySqlColumnType.VARCHAR);
		info.setMaxSize(ColumnConstants.MAX_MYSQL_VARCHAR_INDEX_LENGTH+1);
		// call under test
		String results = info.createIndexDefinition();
		assertEquals("_C123_IDX (_C123_(255))", results);
	}
	
	@Test
	public void testCreateIndexDefinitionBigInt(){
		DatabaseColumnInfo info = new DatabaseColumnInfo();
		info.setColumnName("_C123_");
		info.setIndexName("_C123_IDX");
		info.setType(MySqlColumnType.BIGINT);
		// call under test
		String results = info.createIndexDefinition();
		assertEquals("_C123_IDX (_C123_)", results);
	}
	
	@ParameterizedTest
	@EnumSource(ColumnTypeListMappings.class)
	public void testCreateIndexDefinitionWithListType(ColumnTypeListMappings listTypeMapping){
		DatabaseColumnInfo info = new DatabaseColumnInfo();
		info.setColumnName("_C123_");
		info.setIndexName("_C123_IDX");
		info.setType(MySqlColumnType.JSON);
		info.setColumnType(listTypeMapping.getListType());
		// call under test
		String results = info.createIndexDefinition();
		if (ColumnTypeListMappings.STRING == listTypeMapping) {
			assertEquals("_C123_IDX ((CAST(_C123_ AS CHAR(255) ARRAY)))", results);
		} else {
			assertEquals("_C123_IDX ((CAST(_C123_ AS "+ ColumnTypeInfo.getInfoForType(listTypeMapping.getNonListType()).getMySqlType().getMySqlCastType() +" ARRAY)))", results);
		}
	}
	
	@Test
	public void testCreateIndexDefinitionNullColumnName(){
		DatabaseColumnInfo info = new DatabaseColumnInfo();
		info.setColumnName(null);
		info.setIndexName("_C123_IDX");
		info.setType(MySqlColumnType.BIGINT);
		
		assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			info.createIndexDefinition();
		});
	}
	
	@Test
	public void testCreateIndexDefinitionNullIndexName(){
		DatabaseColumnInfo info = new DatabaseColumnInfo();
		info.setColumnName("_C123_");
		info.setIndexName(null);
		info.setType(MySqlColumnType.BIGINT);
		
		assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			info.createIndexDefinition();
		});
	}
	
	@Test
	public void testCreateIndexDefinitionNullType(){
		DatabaseColumnInfo info = new DatabaseColumnInfo();
		info.setColumnName("_C123_");
		info.setIndexName("_C123_IDX");
		info.setType(null);
		
		assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			info.createIndexDefinition();
		});
	}
	
	@Test
	public void testCARDINALITY_COMPARATOR(){
		DatabaseColumnInfo one = new DatabaseColumnInfo();
		one.setCardinality(1L);
		DatabaseColumnInfo two = new DatabaseColumnInfo();
		two.setCardinality(2L);
		int compare = DatabaseColumnInfo.CARDINALITY_COMPARATOR.compare(one, two);
		assertEquals(-1, compare);
	}
	
	@Test
	public void testCARDINALITY_COMPARATOROneCardinalityNull(){
		DatabaseColumnInfo one = new DatabaseColumnInfo();
		one.setCardinality(null);
		DatabaseColumnInfo two = new DatabaseColumnInfo();
		two.setCardinality(2L);
		
		assertThrows(IllegalArgumentException.class, () -> {
			// call under test.
			DatabaseColumnInfo.CARDINALITY_COMPARATOR.compare(one, two);
		});
	}
	
	@Test
	public void testCARDINALITY_COMPARATORTwoCardinalityNull(){
		DatabaseColumnInfo one = new DatabaseColumnInfo();
		one.setCardinality(1L);
		DatabaseColumnInfo two = new DatabaseColumnInfo();
		two.setCardinality(null);
		
		assertThrows(IllegalArgumentException.class, () -> {
			// call under test.
			DatabaseColumnInfo.CARDINALITY_COMPARATOR.compare(one, two);
		});
	}
	
	@Test
	public void testIsMetadataRowId(){
		DatabaseColumnInfo info = new DatabaseColumnInfo();
		info.setColumnName(TableConstants.ROW_ID);
		assertTrue(info.isMetadata());
	}
	
	@Test
	public void testIsMetadataRowVersion(){
		DatabaseColumnInfo info = new DatabaseColumnInfo();
		info.setColumnName(TableConstants.ROW_VERSION);
		assertTrue(info.isMetadata());
	}
	
	@Test
	public void testIsMetadataEtag(){
		DatabaseColumnInfo info = new DatabaseColumnInfo();
		info.setColumnName(TableConstants.ROW_ETAG);
		assertTrue(info.isMetadata());
	}
	
	@Test
	public void testIsMetadataBenefactor(){
		DatabaseColumnInfo info = new DatabaseColumnInfo();
		info.setColumnName(TableConstants.ROW_BENEFACTOR);
		assertTrue(info.isMetadata());
	}
	
	@Test
	public void testIsMetadataSearchContent(){
		DatabaseColumnInfo info = new DatabaseColumnInfo();
		info.setColumnName(TableConstants.ROW_SEARCH_CONTENT);
		assertTrue(info.isMetadata());
	}
	
	@Test
	public void testIsRowIdOrVersionNot(){
		DatabaseColumnInfo info = new DatabaseColumnInfo();
		info.setColumnName("_C123_");
		assertFalse(info.isMetadata());
	}
	
	@ParameterizedTest
	@EnumSource(ColumnType.class)
	public void testIsCreateIndex(ColumnType columnType) {
		DatabaseColumnInfo info = new DatabaseColumnInfo();
		
		info.setColumnName("_C123_");
		info.setColumnType(columnType);
		info.setType(ColumnTypeInfo.getInfoForType(columnType).getMySqlType());
		
		if (ColumnType.JSON == columnType || ColumnType.LARGETEXT == columnType) {
			assertFalse(info.isCreateIndex());
		} else {
			assertTrue(info.isCreateIndex());			
		}
	}
	
	@Test
	public void testIsCreateIndexWithMetadataColumns() {
		TableConstants.RESERVED_COLUMNS_NAMES.forEach( metadataColumnName -> {			
			DatabaseColumnInfo info = new DatabaseColumnInfo();
			
			info.setColumnName(metadataColumnName);
			
			assertFalse(info.isCreateIndex());
		});
	}
	
}
