package org.sagebionetworks.repo.manager.search;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.sagebionetworks.repo.model.dao.table.TableType;
import org.sagebionetworks.repo.model.dbo.dao.table.TableModelTestUtils;
import org.sagebionetworks.repo.model.entity.IdAndVersion;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.repo.model.table.SelectColumn;
import org.sagebionetworks.table.cluster.QueryTranslator;
import org.sagebionetworks.table.cluster.SchemaProvider;
import org.sagebionetworks.table.cluster.TranslatedQuery;
import org.sagebionetworks.table.cluster.description.IndexDescription;
import org.sagebionetworks.table.cluster.description.IndexDescriptionLookup;
import org.sagebionetworks.table.cluster.description.MaterializedViewIndexDescription;
import org.sagebionetworks.table.cluster.description.ViewIndexDescription;
import org.sagebionetworks.table.query.ParseException;
import org.sagebionetworks.table.query.model.SqlContext;

public class SearchIndexBenefactorQueryTest {

	private static final Long USER_ID = 1L;

	// Two entity views joined on a shared "studyId" column, and a materialized view over them. Ids and
	// column ids match the lib-table-cluster TwoViewMaterializedView fixture so the translated names are
	// stable (T801/T802/T803, _C701_/_C702_/_C703_, ROW_BENEFACTOR__A0/__A1).
	private final IdAndVersion leftViewId = IdAndVersion.parse("syn801");
	private final IdAndVersion rightViewId = IdAndVersion.parse("syn802");
	private final IdAndVersion mvId = IdAndVersion.parse("syn803");
	private final IndexDescription leftView = new ViewIndexDescription(leftViewId, TableType.entityview, -1L);
	private final IndexDescription rightView = new ViewIndexDescription(rightViewId, TableType.entityview, -1L);
	private final ColumnModel leftStudy = TableModelTestUtils.createColumn(701L, "studyId", ColumnType.INTEGER);
	private final ColumnModel rightStudy = TableModelTestUtils.createColumn(702L, "studyId", ColumnType.INTEGER);
	private final ColumnModel mvStudy = TableModelTestUtils.createColumn(703L, "studyId", ColumnType.INTEGER);
	private final List<ColumnModel> mvSchema = List.of(mvStudy);
	private final String mvDefiningSql = "select " + leftViewId + ".studyId from " + leftViewId + " join " + rightViewId
			+ " on (" + leftViewId + ".studyId = " + rightViewId + ".studyId) order by " + leftViewId + ".studyId";

	private final SchemaProvider schemaProvider = new SchemaProvider() {
		@Override
		public TableType getTableType(IdAndVersion id) {
			return TableType.entityview;
		}

		@Override
		public List<ColumnModel> getTableSchema(IdAndVersion id) {
			if (leftViewId.equals(id)) {
				return List.of(leftStudy);
			}
			if (rightViewId.equals(id)) {
				return List.of(rightStudy);
			}
			if (mvId.equals(id)) {
				return mvSchema;
			}
			throw new IllegalStateException("Unexpected table: " + id);
		}

		@Override
		public ColumnModel getColumnModel(String id) {
			if (leftStudy.getId().equals(id)) {
				return leftStudy;
			}
			if (rightStudy.getId().equals(id)) {
				return rightStudy;
			}
			if (mvStudy.getId().equals(id)) {
				return mvStudy;
			}
			throw new IllegalStateException("Unexpected column: " + id);
		}
	};

	private final IndexDescriptionLookup lookup = id -> {
		if (leftViewId.equals(id)) {
			return leftView;
		}
		if (rightViewId.equals(id)) {
			return rightView;
		}
		throw new IllegalStateException("Unexpected table: " + id);
	};

	private final MaterializedViewIndexDescription mvIndex = new MaterializedViewIndexDescription(mvId, mvDefiningSql,
			lookup);

	@Test
	public void testBuildWithBenefactorColumnsWithMaterializedViewOverTwoViews() throws ParseException {
		QueryTranslator base = QueryTranslator.builder().sql("select * from " + mvId).schemaProvider(schemaProvider)
				.sqlContext(SqlContext.query).indexDescription(mvIndex).userId(USER_ID).build();

		// call under test
		TranslatedQuery query = SearchIndexBenefactorQuery.buildWithBenefactorColumns(base, mvIndex);

		// The two physical benefactor columns are spliced in after the document column and ahead of the
		// by-name ROW_ID/ROW_VERSION metadata.
		assertEquals("SELECT _C703_, ROW_BENEFACTOR__A0, ROW_BENEFACTOR__A1, ROW_ID, ROW_VERSION FROM T803",
				query.getOutputSQL());
		// The benefactor columns are mirrored into the result headers as INTEGER columns, in
		// getBenefactors() order, after the document column. ROW_ID/ROW_VERSION are read by name and are
		// not select headers.
		assertEquals(List.of(
				new SelectColumn().setName("studyId").setColumnType(ColumnType.INTEGER).setId("703"),
				new SelectColumn().setName("ROW_BENEFACTOR__A0").setColumnType(ColumnType.INTEGER),
				new SelectColumn().setName("ROW_BENEFACTOR__A1").setColumnType(ColumnType.INTEGER)),
				query.getSelectColumns());
	}

	@Test
	public void testBuildWithBenefactorColumnsWithViewSource() throws ParseException {
		// A view source has no per-dependency benefactor columns; its single benefactor flows by name via
		// Row.benefactorId. The helper must return the base query unchanged.
		QueryTranslator base = QueryTranslator.builder().sql("select studyId from " + leftViewId)
				.schemaProvider(schemaProvider).sqlContext(SqlContext.query).indexDescription(leftView)
				.userId(USER_ID).build();

		// call under test
		TranslatedQuery query = SearchIndexBenefactorQuery.buildWithBenefactorColumns(base, leftView);

		assertEquals(base.getOutputSQL(), query.getOutputSQL());
		assertEquals(base.getSelectColumns(), query.getSelectColumns());
	}
}
