package org.sagebionetworks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.client.SynapseAdminClient;
import org.sagebionetworks.client.exceptions.SynapseBadRequestException;
import org.sagebionetworks.client.exceptions.SynapseException;
import org.sagebionetworks.repo.model.Project;
import org.sagebionetworks.repo.model.search.table.SearchIndex;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.repo.model.table.TableEntity;

@ExtendWith(ITTestExtension.class)
public class ITSearchIndexEntityTest {

	private SynapseAdminClient adminSynapse;
	private Project project;
	private TableEntity table;
	private SearchIndex searchIndex;

	public ITSearchIndexEntityTest(SynapseAdminClient adminSynapse) {
		this.adminSynapse = adminSynapse;
	}

	@BeforeEach
	public void before() throws SynapseException {
		adminSynapse.clearAllLocks();
		project = new Project();
		project = adminSynapse.createEntity(project);

		ColumnModel column = new ColumnModel();
		column.setName("studyName");
		column.setColumnType(ColumnType.STRING);
		column.setMaximumSize(100L);
		column = adminSynapse.createColumnModel(column);

		table = new TableEntity();
		table.setParentId(project.getId());
		table.setName("Source Table");
		table.setColumnIds(List.of(column.getId()));
		table = adminSynapse.createEntity(table);
	}

	@AfterEach
	public void after() throws Exception {
		if (searchIndex != null) {
			adminSynapse.deleteEntity(searchIndex, true);
		}
		if (project != null) {
			adminSynapse.deleteEntity(project, true);
		}
	}

	@Test
	public void testCRUDWithSearchIndex() throws SynapseException {
		searchIndex = new SearchIndex();
		searchIndex.setParentId(project.getId());
		searchIndex.setName("Test Search Index");
		searchIndex.setDefiningSQL("SELECT * FROM " + table.getId());

		// call under test — CREATE
		searchIndex = adminSynapse.createEntity(searchIndex);

		assertNotNull(searchIndex.getId());
		assertNotNull(searchIndex.getEtag());
		assertEquals("Test Search Index", searchIndex.getName());
		assertEquals("SELECT * FROM " + table.getId(), searchIndex.getDefiningSQL());

		// call under test — GET
		SearchIndex retrieved = adminSynapse.getEntity(searchIndex.getId(), SearchIndex.class);

		assertEquals(searchIndex, retrieved);

		// call under test — UPDATE
		searchIndex.setName("Updated Search Index");
		searchIndex.setDefiningSQL("SELECT studyName FROM " + table.getId());
		searchIndex = adminSynapse.putEntity(searchIndex);

		assertEquals("Updated Search Index", searchIndex.getName());
		assertEquals("SELECT studyName FROM " + table.getId(), searchIndex.getDefiningSQL());

		// call under test — DELETE
		adminSynapse.deleteEntity(searchIndex, true);
		searchIndex = null;
	}

	@Test
	public void testCreateWithSearchConfigurationId() throws SynapseException {
		searchIndex = new SearchIndex();
		searchIndex.setParentId(project.getId());
		searchIndex.setName("Configured Search Index");
		searchIndex.setDefiningSQL("SELECT * FROM " + table.getId());
		searchIndex.setSearchConfigurationId("12345");

		// call under test
		searchIndex = adminSynapse.createEntity(searchIndex);

		assertNotNull(searchIndex.getId());
		assertEquals("12345", searchIndex.getSearchConfigurationId());

		// Verify round-trip via GET
		SearchIndex retrieved = adminSynapse.getEntity(searchIndex.getId(), SearchIndex.class);

		assertEquals(searchIndex, retrieved);
	}

	// Bare double-quoted strings parse as SQL identifiers, not string literals;
	// an unknown identifier must fail synchronously with a 400 at PUT time.
	@Test
	public void testUpdateWithBareDoubleQuotedUnknownIdentifierRejected() throws SynapseException {
		searchIndex = new SearchIndex();
		searchIndex.setParentId(project.getId());
		searchIndex.setName("Reject test SearchIndex");
		searchIndex.setDefiningSQL("SELECT * FROM " + table.getId());
		searchIndex = adminSynapse.createEntity(searchIndex);

		final String beforeEtag = searchIndex.getEtag();
		final String entityId = searchIndex.getId();
		searchIndex.setDefiningSQL("SELECT studyName, \"tag\" FROM " + table.getId());

		// call under test
		String errorMessage = assertThrows(SynapseBadRequestException.class,
				() -> adminSynapse.putEntity(searchIndex)).getMessage();
		assertTrue(errorMessage.contains("Unknown column"),
				"expected error message to mention 'Unknown column', got: " + errorMessage);
		assertTrue(errorMessage.contains("tag"),
				"expected error message to mention 'tag', got: " + errorMessage);

		// The failed PUT rolled back — reload and confirm the entity is unchanged.
		searchIndex = adminSynapse.getEntity(entityId, SearchIndex.class);
		assertEquals(beforeEtag, searchIndex.getEtag());
		assertEquals("SELECT * FROM " + table.getId(), searchIndex.getDefiningSQL());
	}
}
