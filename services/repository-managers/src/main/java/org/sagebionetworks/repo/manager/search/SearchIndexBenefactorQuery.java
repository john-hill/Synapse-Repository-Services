package org.sagebionetworks.repo.manager.search;

import java.util.ArrayList;
import java.util.List;

import org.sagebionetworks.repo.model.dao.table.TableType;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.repo.model.table.SelectColumn;
import org.sagebionetworks.table.cluster.CachedQueryRequest;
import org.sagebionetworks.table.cluster.QueryTranslator;
import org.sagebionetworks.table.cluster.TranslatedQuery;
import org.sagebionetworks.table.cluster.description.BenefactorDescription;
import org.sagebionetworks.table.cluster.description.IndexDescription;
import org.sagebionetworks.table.query.model.DerivedColumn;
import org.sagebionetworks.table.query.model.SelectList;
import org.sagebionetworks.table.query.util.SqlElementUtils;

/**
 * Builds the SearchIndex build-time query: a base query-context translator of the source's defining
 * SQL with the source's per-dependency benefactor columns spliced into the select so each streamed
 * row carries its benefactor values.
 */
class SearchIndexBenefactorQuery {

	/**
	 * Splice the source index's physical benefactor columns into the base query so the streamed rows
	 * carry one benefactor value per source dependency (in {@link IndexDescription#getBenefactors()}
	 * order).
	 * <p>
	 * The columns must land <em>before</em> the trailing by-name metadata columns ({@code ROW_ID,
	 * ROW_VERSION}) and be mirrored into the select-column headers as INTEGER columns, because
	 * {@code SQLTranslatorUtils.readRow} reads {@code Row.values} positionally (the first
	 * {@code getSelectColumns().length} result columns) while reading {@code ROW_ID}/{@code ROW_VERSION}
	 * by name. The base translator's {@code getSelectColumns()} holds only the document columns
	 * (metadata columns are added to the SQL by name, not to the headers), so the splice index is
	 * simply that header count.
	 * <p>
	 * Only a materialized view stores its per-dependency benefactors as physical columns of its index
	 * table that must be spliced in here. A view's single benefactor is already read by name into
	 * {@code Row.benefactorId} (via the by-name metadata columns the base query emits), and a table has
	 * none, so for any non-materialized-view source the base query is returned unchanged.
	 *
	 * @param base   a query-context {@link QueryTranslator} built from the source's defining SQL.
	 * @param source the source's {@link IndexDescription}.
	 * @return a {@link TranslatedQuery} ready to stream through {@code TableIndexDAO.queryAsStream}.
	 */
	static TranslatedQuery buildWithBenefactorColumns(QueryTranslator base, IndexDescription source) {
		if (!TableType.materializedview.equals(source.getTableType())) {
			return CachedQueryRequest.clone(base);
		}

		List<String> benefactorColumnNames = new ArrayList<>();
		for (BenefactorDescription desc : source.getBenefactors()) {
			benefactorColumnNames.add(desc.getBenefactorColumnName());
		}
		if (benefactorColumnNames.isEmpty()) {
			return CachedQueryRequest.clone(base);
		}

		// The document columns are the only headers on the base translator; the benefactor columns
		// splice in just after them, ahead of the by-name ROW_ID/ROW_VERSION metadata in the SQL.
		int spliceIndex = base.getSelectColumns().size();
		SelectList selectList = base.getTranslatedModel().getFirstElementOfType(SelectList.class);
		for (int i = 0; i < benefactorColumnNames.size(); i++) {
			DerivedColumn column = SqlElementUtils.createNonQuotedDerivedColumn(benefactorColumnNames.get(i));
			selectList.getColumns().add(spliceIndex + i, column);
		}
		selectList.recursiveSetParent();
		String outputSQL = base.getTranslatedModel().toSql();

		List<SelectColumn> headers = new ArrayList<>(base.getSelectColumns());
		for (String name : benefactorColumnNames) {
			headers.add(new SelectColumn().setName(name).setColumnType(ColumnType.INTEGER));
		}

		return CachedQueryRequest.clone(base).setOutputSQL(outputSQL).setSelectColumns(headers);
	}
}
