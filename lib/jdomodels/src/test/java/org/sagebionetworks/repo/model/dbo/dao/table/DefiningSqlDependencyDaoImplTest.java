package org.sagebionetworks.repo.model.dbo.dao.table;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.repo.model.EntityType;
import org.sagebionetworks.repo.model.NodeDAO;
import org.sagebionetworks.repo.model.entity.IdAndVersion;
import org.sagebionetworks.repo.model.helper.NodeDaoObjectHelper;
import org.sagebionetworks.repo.model.jdo.KeyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import com.google.common.collect.ImmutableSet;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = { "classpath:jdomodels-test-context.xml" })
public class DefiningSqlDependencyDaoImplTest {

	private static final String MV_TYPE = EntityType.materializedview.name();
	private static final String SEARCH_TYPE = EntityType.searchindex.name();

	@Autowired
	private NodeDAO nodeDao;

	@Autowired
	private NodeDaoObjectHelper nodeHelper;

	@Autowired
	private DefiningSqlDependencyDaoImpl dao;

	private IdAndVersion viewId;

	@BeforeEach
	public void before() {
		nodeDao.truncateAll();

		String nodeId = nodeHelper.create(node -> {
			node.setNodeType(EntityType.materializedview);
		}).getId();

		viewId = KeyFactory.idAndVersion(nodeId, null);
	}

	@AfterEach
	public void after() {
		nodeDao.truncateAll();
	}

	@Test
	public void testAddAndGetSourceTables() {

		Set<IdAndVersion> sourceTables = ImmutableSet.of(IdAndVersion.parse("syn123"), IdAndVersion.parse("456"),
				IdAndVersion.parse("syn123.2"));

		// Call under test
		dao.addSourceTables(viewId, MV_TYPE, sourceTables);

		assertEquals(sourceTables, dao.getSourceTables(viewId));
	}

	@Test
	public void testAddAndGetSourceTablesWithVersion() {

		IdAndVersion viewIdWithoutVersion = viewId;

		Set<IdAndVersion> sourceTablesNoVersion = ImmutableSet.of(IdAndVersion.parse("syn123"),
				IdAndVersion.parse("789"));

		dao.addSourceTables(viewIdWithoutVersion, MV_TYPE, sourceTablesNoVersion);

		viewId = IdAndVersion.newBuilder().setId(viewId.getId()).setVersion(2L).build();

		Set<IdAndVersion> sourceTables = ImmutableSet.of(IdAndVersion.parse("syn123"), IdAndVersion.parse("456"),
				IdAndVersion.parse("syn123.2"));

		// Call under test
		dao.addSourceTables(viewId, MV_TYPE, sourceTables);

		assertEquals(sourceTables, dao.getSourceTables(viewId));
		assertEquals(sourceTablesNoVersion, dao.getSourceTables(viewIdWithoutVersion));
	}

	@Test
	public void testAddAndGetSourceTablesEmpty() {

		Set<IdAndVersion> sourceTables = Collections.emptySet();

		// Call under test
		assertEquals(sourceTables, dao.getSourceTables(viewId));

		// Call under test
		dao.addSourceTables(viewId, MV_TYPE, sourceTables);

		assertEquals(sourceTables, dao.getSourceTables(viewId));
	}

	@Test
	public void testAddAndGetSourceTablesWithExisting() {

		Set<IdAndVersion> sourceTables = ImmutableSet.of(IdAndVersion.parse("syn123"), IdAndVersion.parse("456"));

		dao.addSourceTables(viewId, MV_TYPE, sourceTables);

		Set<IdAndVersion> additionalSourceTables = ImmutableSet.of(IdAndVersion.parse("syn123"),
				IdAndVersion.parse("syn123.2"));

		// Call under test
		dao.addSourceTables(viewId, MV_TYPE, additionalSourceTables);

		Set<IdAndVersion> expected = ImmutableSet.of(IdAndVersion.parse("syn123"), IdAndVersion.parse("syn123.2"),
				IdAndVersion.parse("456"));

		assertEquals(expected, dao.getSourceTables(viewId));
	}

	@Test
	public void testDeleteSourceTables() {

		Set<IdAndVersion> sourceTables = ImmutableSet.of(IdAndVersion.parse("syn123"), IdAndVersion.parse("456"));

		dao.addSourceTables(viewId, MV_TYPE, sourceTables);

		Set<IdAndVersion> expected = Collections.emptySet();

		// Call under test
		dao.deleteSourceTables(viewId, sourceTables);

		assertEquals(expected, dao.getSourceTables(viewId));

	}

	@Test
	public void testDeleteSourceTablesPartial() {

		Set<IdAndVersion> sourceTables = ImmutableSet.of(IdAndVersion.parse("syn123"), IdAndVersion.parse("456"));

		dao.addSourceTables(viewId, MV_TYPE, sourceTables);

		Set<IdAndVersion> expected = ImmutableSet.of(IdAndVersion.parse("456"));

		// Call under test
		dao.deleteSourceTables(viewId, ImmutableSet.of(IdAndVersion.parse("123")));

		assertEquals(expected, dao.getSourceTables(viewId));

	}

	@Test
	public void testDeleteSourceTablesWithVersions() {

		IdAndVersion viewIdWithoutVersion = viewId;

		Set<IdAndVersion> sourceTablesNoVersion = ImmutableSet.of(IdAndVersion.parse("syn123"),
				IdAndVersion.parse("456"), IdAndVersion.parse("456.1"), IdAndVersion.parse("123.2"));

		dao.addSourceTables(viewIdWithoutVersion, MV_TYPE, sourceTablesNoVersion);

		viewId = IdAndVersion.newBuilder().setId(viewId.getId()).setVersion(5L).build();

		Set<IdAndVersion> sourceTables = ImmutableSet.of(IdAndVersion.parse("syn123"), IdAndVersion.parse("456"),
				IdAndVersion.parse("456.1"), IdAndVersion.parse("123.2"));

		dao.addSourceTables(viewId, MV_TYPE, sourceTables);

		Set<IdAndVersion> expected = ImmutableSet.of(IdAndVersion.parse("syn123"), IdAndVersion.parse("456.1"));

		// Call under test
		dao.deleteSourceTables(viewId, ImmutableSet.of(IdAndVersion.parse("syn123.2"), IdAndVersion.parse("456")));

		assertEquals(expected, dao.getSourceTables(viewId));
		assertEquals(sourceTablesNoVersion, dao.getSourceTables(viewIdWithoutVersion));

	}

	@Test
	public void testDeleteSourceTablesWithEmptySet() {

		Set<IdAndVersion> sourceTables = ImmutableSet.of(IdAndVersion.parse("syn123"), IdAndVersion.parse("456"));

		dao.addSourceTables(viewId, MV_TYPE, sourceTables);

		Set<IdAndVersion> expected = sourceTables;

		// Call under test
		dao.deleteSourceTables(viewId, Collections.emptySet());

		assertEquals(expected, dao.getSourceTables(viewId));

	}

	@Test
	public void testDeleteSourceTablesWithNoData() {

		Set<IdAndVersion> sourceTables = ImmutableSet.of(IdAndVersion.parse("syn123"), IdAndVersion.parse("456"));

		Set<IdAndVersion> expected = Collections.emptySet();

		// Call under test
		dao.deleteSourceTables(viewId, sourceTables);

		assertEquals(expected, dao.getSourceTables(viewId));

	}

	@Test
	public void testDeleteObject() {

		Set<IdAndVersion> sourceTables = ImmutableSet.of(IdAndVersion.parse("syn123"), IdAndVersion.parse("456"));

		dao.addSourceTables(viewId, MV_TYPE, sourceTables);

		// Call under test
		dao.deleteObject(viewId);

		assertEquals(Collections.emptySet(), dao.getSourceTables(viewId));
	}

	@Test
	public void testSetAndGetSourceTable() {

		IdAndVersion source = IdAndVersion.parse("syn123.2");

		// Call under test
		dao.setSourceTable(viewId, SEARCH_TYPE, source);

		assertEquals(Optional.of(source), dao.getSourceTable(viewId));
		assertEquals(ImmutableSet.of(source), dao.getSourceTables(viewId));
	}

	@Test
	public void testSetSourceTableReplacesExisting() {

		dao.setSourceTable(viewId, SEARCH_TYPE, IdAndVersion.parse("syn123"));

		IdAndVersion replacement = IdAndVersion.parse("syn999.4");

		// Call under test
		dao.setSourceTable(viewId, SEARCH_TYPE, replacement);

		assertEquals(Optional.of(replacement), dao.getSourceTable(viewId));
		assertEquals(ImmutableSet.of(replacement), dao.getSourceTables(viewId));
	}

	@Test
	public void testGetSourceTableWithNoData() {

		// Call under test
		assertEquals(Optional.empty(), dao.getSourceTable(viewId));
	}

	@Test
	public void testGetDependentObjectIdsPage() {

		long limit = 10;
		long offset = 0;

		IdAndVersion sourceTableId = IdAndVersion.parse("syn123");

		Set<IdAndVersion> sourceTables = ImmutableSet.of(sourceTableId, IdAndVersion.parse("456"));

		dao.addSourceTables(viewId, MV_TYPE, sourceTables);

		List<IdAndVersion> expected = Arrays.asList(viewId);

		// Call under test
		List<IdAndVersion> result = dao.getDependentObjectIdsPage(MV_TYPE, sourceTableId, limit, offset);

		assertEquals(expected, result);
	}

	@Test
	public void testGetDependentObjectIdsPageMultiplePages() {

		IdAndVersion sourceTableId = IdAndVersion.parse("syn123");

		// Multiple versions that reference the same source table
		dao.addSourceTables(viewId, MV_TYPE,
				ImmutableSet.of(sourceTableId, IdAndVersion.parse("456"), IdAndVersion.parse("789")));

		IdAndVersion viewIdV2 = IdAndVersion.parse(viewId.getId() + ".2");

		dao.addSourceTables(viewIdV2, MV_TYPE,
				ImmutableSet.of(sourceTableId, IdAndVersion.parse("654"), IdAndVersion.parse("345")));

		IdAndVersion viewIdV3 = IdAndVersion.parse(viewId.getId() + ".3");

		dao.addSourceTables(viewIdV3, MV_TYPE,
				ImmutableSet.of(sourceTableId, IdAndVersion.parse("456"), IdAndVersion.parse("345")));

		long limit = 2;
		long offset = 0;

		List<IdAndVersion> expectedfirstPage = Arrays.asList(viewId, viewIdV2);

		// Call under test
		List<IdAndVersion> firstPage = dao.getDependentObjectIdsPage(MV_TYPE, sourceTableId, limit, offset);

		assertEquals(expectedfirstPage, firstPage);

		offset = 2;

		List<IdAndVersion> expectedSecondPage = Arrays.asList(viewIdV3);

		// Call under test
		List<IdAndVersion> secondPage = dao.getDependentObjectIdsPage(MV_TYPE, sourceTableId, limit, offset);

		assertEquals(expectedSecondPage, secondPage);
	}

	@Test
	public void testGetDependentObjectIdsPageWithVersion() {

		long limit = 10;
		long offset = 0;

		IdAndVersion sourceTableId = IdAndVersion.parse("syn123.2");

		Set<IdAndVersion> sourceTables = ImmutableSet.of(sourceTableId, IdAndVersion.parse("456"));

		dao.addSourceTables(viewId, MV_TYPE, sourceTables);

		List<IdAndVersion> expected = Arrays.asList(viewId);

		// Call under test
		List<IdAndVersion> result = dao.getDependentObjectIdsPage(MV_TYPE, sourceTableId, limit, offset);

		assertEquals(expected, result);
	}

	@Test
	public void testGetDependentObjectIdsPageWithOverlappingIds() {

		long limit = 10;
		long offset = 0;

		IdAndVersion sourceTableId = IdAndVersion.parse("syn123");

		Set<IdAndVersion> sourceTables = ImmutableSet.of(sourceTableId, IdAndVersion.parse("456"));

		dao.addSourceTables(viewId, MV_TYPE, sourceTables);

		// Another version of the view that uses the same source table
		IdAndVersion viewWithVersion = IdAndVersion.newBuilder().setId(viewId.getId()).setVersion(5L).build();

		dao.addSourceTables(viewWithVersion, MV_TYPE, ImmutableSet.of(sourceTableId));

		List<IdAndVersion> expected = Arrays.asList(viewId, viewWithVersion);

		// Call under test
		List<IdAndVersion> result = dao.getDependentObjectIdsPage(MV_TYPE, sourceTableId, limit, offset);

		assertEquals(expected, result);
	}

	@Test
	public void testGetDependentObjectIdsPageWithNonOverlappingIds() {

		long limit = 10;
		long offset = 0;

		IdAndVersion sourceTableId = IdAndVersion.parse("syn123");

		Set<IdAndVersion> sourceTables = ImmutableSet.of(sourceTableId, IdAndVersion.parse("456"));

		dao.addSourceTables(viewId, MV_TYPE, sourceTables);

		// Another version of the view that does not use the source table
		IdAndVersion viewWithVersion = IdAndVersion.newBuilder().setId(viewId.getId()).setVersion(5L).build();

		dao.addSourceTables(viewWithVersion, MV_TYPE,
				ImmutableSet.of(IdAndVersion.parse("syn123.2"), IdAndVersion.parse("456")));

		List<IdAndVersion> expected = Arrays.asList(viewId);

		// Call under test
		List<IdAndVersion> result = dao.getDependentObjectIdsPage(MV_TYPE, sourceTableId, limit, offset);

		assertEquals(expected, result);
	}

	@Test
	public void testGetDependentObjectIdsPageWithNoData() {

		long limit = 10;
		long offset = 0;

		IdAndVersion sourceTableId = IdAndVersion.parse("syn123");

		List<IdAndVersion> expected = Collections.emptyList();

		// Call under test
		List<IdAndVersion> result = dao.getDependentObjectIdsPage(MV_TYPE, sourceTableId, limit, offset);

		assertEquals(expected, result);
	}

	@Test
	public void testGetDependentObjectIdsPageFiltersByObjectType() {

		// A materialized view and a search index that both depend on the same source table.
		String searchNodeId = nodeHelper.create(node -> {
			node.setNodeType(EntityType.searchindex);
		}).getId();
		IdAndVersion searchIndexId = KeyFactory.idAndVersion(searchNodeId, null);

		IdAndVersion sourceTableId = IdAndVersion.parse("syn123");

		dao.addSourceTables(viewId, MV_TYPE, ImmutableSet.of(sourceTableId));
		dao.addSourceTables(searchIndexId, SEARCH_TYPE, ImmutableSet.of(sourceTableId));

		long limit = 10;
		long offset = 0;

		// Call under test: each type only sees its own dependents
		assertEquals(Arrays.asList(viewId), dao.getDependentObjectIdsPage(MV_TYPE, sourceTableId, limit, offset));
		assertEquals(Arrays.asList(searchIndexId),
				dao.getDependentObjectIdsPage(SEARCH_TYPE, sourceTableId, limit, offset));
	}

}
