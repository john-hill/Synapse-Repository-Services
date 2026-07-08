package org.sagebionetworks.repo.model.dbo.search;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

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

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = { "classpath:jdomodels-test-context.xml" })
public class SearchIndexSourceTableDaoImplTest {

	@Autowired
	private NodeDAO nodeDao;

	@Autowired
	private NodeDaoObjectHelper nodeHelper;

	@Autowired
	private SearchIndexSourceTableDaoImpl dao;

	private IdAndVersion searchIndexId;
	private IdAndVersion otherSearchIndexId;

	@BeforeEach
	public void before() {
		nodeDao.truncateAll();

		searchIndexId = KeyFactory.idAndVersion(nodeHelper.create(node -> {
			node.setNodeType(EntityType.searchindex);
		}).getId(), null);

		otherSearchIndexId = KeyFactory.idAndVersion(nodeHelper.create(node -> {
			node.setNodeType(EntityType.searchindex);
		}).getId(), null);
	}

	@AfterEach
	public void after() {
		nodeDao.truncateAll();
	}

	@Test
	public void testSetSourceTableAndGetDependents() {
		IdAndVersion sourceTableId = IdAndVersion.parse("syn123");

		// call under test
		dao.setSourceTable(searchIndexId, sourceTableId);

		assertEquals(Arrays.asList(searchIndexId.getId()), dao.getDependentSearchIndexIds(sourceTableId));
	}

	@Test
	public void testSetSourceTableWithVersion() {
		IdAndVersion sourceTableId = IdAndVersion.parse("syn123.2");

		// call under test
		dao.setSourceTable(searchIndexId, sourceTableId);

		assertEquals(Arrays.asList(searchIndexId.getId()), dao.getDependentSearchIndexIds(sourceTableId));
		// The versionless source is a distinct edge — must not match.
		assertEquals(Collections.emptyList(), dao.getDependentSearchIndexIds(IdAndVersion.parse("syn123")));
	}

	@Test
	public void testSetSourceTableIsIdempotentReplacement() {
		dao.setSourceTable(searchIndexId, IdAndVersion.parse("syn123"));

		// call under test — re-register the same SearchIndex against a different source.
		dao.setSourceTable(searchIndexId, IdAndVersion.parse("syn456"));

		// The old edge is gone (replaced), the new one is present.
		assertEquals(Collections.emptyList(), dao.getDependentSearchIndexIds(IdAndVersion.parse("syn123")));
		assertEquals(Arrays.asList(searchIndexId.getId()), dao.getDependentSearchIndexIds(IdAndVersion.parse("syn456")));
	}

	@Test
	public void testGetDependentsWithMultipleSearchIndexes() {
		IdAndVersion sharedSource = IdAndVersion.parse("syn123");

		dao.setSourceTable(searchIndexId, sharedSource);
		dao.setSourceTable(otherSearchIndexId, sharedSource);

		// Both SearchIndexes depend on the same source, ordered by id.
		assertEquals(Arrays.asList(searchIndexId.getId(), otherSearchIndexId.getId()),
				dao.getDependentSearchIndexIds(sharedSource));
	}

	@Test
	public void testGetDependentsWithDecoySource() {
		dao.setSourceTable(searchIndexId, IdAndVersion.parse("syn123"));
		dao.setSourceTable(otherSearchIndexId, IdAndVersion.parse("syn456"));

		// call under test — only the SearchIndex bound to syn123 is returned.
		assertEquals(Arrays.asList(searchIndexId.getId()), dao.getDependentSearchIndexIds(IdAndVersion.parse("syn123")));
	}

	@Test
	public void testGetDependentsWithNoData() {
		assertEquals(Collections.emptyList(), dao.getDependentSearchIndexIds(IdAndVersion.parse("syn123")));
	}

	@Test
	public void testGetSourceTable() {
		IdAndVersion sourceTableId = IdAndVersion.parse("syn123");
		dao.setSourceTable(searchIndexId, sourceTableId);
		// A decoy edge on a different SearchIndex must not be returned.
		dao.setSourceTable(otherSearchIndexId, IdAndVersion.parse("syn456"));

		// call under test
		assertEquals(Optional.of(sourceTableId), dao.getSourceTable(searchIndexId));
	}

	@Test
	public void testGetSourceTableWithVersion() {
		IdAndVersion sourceTableId = IdAndVersion.parse("syn123.2");
		dao.setSourceTable(searchIndexId, sourceTableId);

		// call under test — the stored version round-trips.
		assertEquals(Optional.of(sourceTableId), dao.getSourceTable(searchIndexId));
	}

	@Test
	public void testGetSourceTableWithNoData() {
		// call under test — no registered edge for this SearchIndex.
		assertEquals(Optional.empty(), dao.getSourceTable(searchIndexId));
	}

	@Test
	public void testDelete() {
		IdAndVersion sourceTableId = IdAndVersion.parse("syn123");
		dao.setSourceTable(searchIndexId, sourceTableId);

		// call under test
		dao.delete(searchIndexId);

		assertEquals(Collections.emptyList(), dao.getDependentSearchIndexIds(sourceTableId));
	}

	@Test
	public void testDeleteWithNoData() {
		// call under test — deleting a non-existent edge is a no-op.
		dao.delete(searchIndexId);

		assertEquals(Collections.emptyList(), dao.getDependentSearchIndexIds(IdAndVersion.parse("syn123")));
	}

	@Test
	public void testNodeDeleteCascadesEdge() {
		IdAndVersion sourceTableId = IdAndVersion.parse("syn123");
		dao.setSourceTable(searchIndexId, sourceTableId);

		// Deleting the SearchIndex node removes its edge via ON DELETE CASCADE.
		nodeDao.delete(searchIndexId.toString());

		assertEquals(Collections.emptyList(), dao.getDependentSearchIndexIds(sourceTableId));
	}

}
