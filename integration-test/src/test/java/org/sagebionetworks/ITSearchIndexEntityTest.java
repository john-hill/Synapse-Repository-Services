package org.sagebionetworks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.client.SynapseAdminClient;
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
}
