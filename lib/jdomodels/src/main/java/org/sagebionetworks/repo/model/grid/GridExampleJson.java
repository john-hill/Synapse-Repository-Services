package org.sagebionetworks.repo.model.grid;

import java.io.StringWriter;
import java.io.Writer;
import java.util.List;

import org.sagebionetworks.repo.model.UnmodifiableXStream;
import org.sagebionetworks.repo.model.grid.query.CellValueFilter;
import org.sagebionetworks.repo.model.grid.query.CellValueOperator;
import org.sagebionetworks.repo.model.grid.query.Query;
import org.sagebionetworks.repo.model.grid.query.RowIdFilter;
import org.sagebionetworks.repo.model.grid.query.RowIsValidFilter;
import org.sagebionetworks.repo.model.grid.query.RowSelectionFilter;
import org.sagebionetworks.repo.model.grid.query.RowValidationResultFilter;
import org.sagebionetworks.repo.model.grid.query.SelectAll;
import org.sagebionetworks.repo.model.grid.query.SelectByName;
import org.sagebionetworks.repo.model.grid.query.ValidationOperator;
import org.sagebionetworks.repo.model.grid.query.function.CountStar;
import org.sagebionetworks.repo.model.grid.update.SetValue;
import org.sagebionetworks.repo.model.grid.update.Update;
import org.sagebionetworks.repo.model.jdo.JDOSecondaryPropertyUtils;

/**
 * Utility to prepare example Query and Update JSON for the grid agent's
 * instructions.
 */
public class GridExampleJson {

	public static void main(String[] args) {
		UnmodifiableXStream X_STREAM = UnmodifiableXStream.builder().allowTypes(QueryExamples.class, QueryExample.class)
				.alias("query_examples", QueryExamples.class).alias("query_example", QueryExample.class)
				.alias("update_examples", UpdateExamples.class).alias("update_example", UpdateExample.class).build();

		StringWriter writer = new StringWriter();
		X_STREAM.toXML(new QueryExamples().setExamples(
				// Example 1
				new QueryExample().setDescription("Return up to 10 rows selecting all columns (no filters).")
						.setQuery_json(JDOSecondaryPropertyUtils.createJSONFromObject(
								new Query().setColumnSelection(List.of(new SelectAll())).setLimit(10L))),
				// Example 2
				new QueryExample().setDescription("Return up to 10 rows selecting two columns by their name (no filters).")
						.setQuery_json(JDOSecondaryPropertyUtils.createJSONFromObject(
							new Query().setColumnSelection(
								List.of(new SelectByName().setColumnName("name"),
										new SelectByName().setColumnName("age"))
							).setLimit(10L))),
				// Example 3
				new QueryExample()
						.setDescription("Return the total number of rows currently in the grid (single count value).")
						.setQuery_json(JDOSecondaryPropertyUtils.createJSONFromObject(
								new Query().setColumnSelection(List.of(new CountStar())).setLimit(1L))),
				// Example 4
				new QueryExample().setDescription(
						"Return up to 50 rows selecting all columns where age > 25 AND JSON schema validation is invalid (isValid = false).")
						.setQuery_json(JDOSecondaryPropertyUtils
								.createJSONFromObject(new Query().setColumnSelection(List.of(new SelectAll()))
										.setFilters(List.of(new CellValueFilter()
												.setColumnName("age").setOperator(CellValueOperator.GREATER_THAN)
												.setValue(List.of(25)), new RowIsValidFilter().setValue(false)))
										.setLimit(50L))),
				// Example 5
				new QueryExample().setDescription(
						"Count the currently selected rows whose validation error message contains 'expected type' (SQL LIKE pattern using % wildcards).")
						.setQuery_json(JDOSecondaryPropertyUtils
								.createJSONFromObject(new Query().setColumnSelection(List.of(new CountStar()))
										.setFilters(List.of(new RowSelectionFilter().setIsSelected(true),
												new RowValidationResultFilter().setOperator(ValidationOperator.LIKE)
														.setValidationResultValue("%expected type%")))
										.setLimit(1L)))),
				writer);

		System.out.println(writerToString(writer));

		writer = new StringWriter();
		X_STREAM.toXML(new UpdateExamples().setExamples(
				// Update Example 1
				new UpdateExample().setDescription("Set age = 25 for rows where age is currently null.")
						.setUpdate_json(JDOSecondaryPropertyUtils.createJSONFromObject(
								new Update().setSet(List.of(new SetValue().setColumnName("age").setValue(25)))
										.setFilters(List.of(new CellValueFilter().setColumnName("age")
												.setOperator(CellValueOperator.IS_NULL))))),
				// Update Example 2
				new UpdateExample().setDescription(
						"For rows where height > 12, set type = 'tall' and footing = null; cap updates at 10 rows.")
						.setUpdate_json(JDOSecondaryPropertyUtils.createJSONFromObject(new Update()
								.setSet(List.of(new SetValue().setColumnName("type").setValue("tall"),
										new SetValue().setColumnName("footing").setValue(null)))
								.setFilters(List.of(new CellValueFilter().setColumnName("height")
										.setOperator(CellValueOperator.GREATER_THAN).setValue(List.of(12))))
								.setLimit(10L))),
				// Update Example 3
				new UpdateExample().setDescription("Set name = 'Dave' for all currently selected rows.")
						.setUpdate_json(JDOSecondaryPropertyUtils.createJSONFromObject(
								new Update().setSet(List.of(new SetValue().setColumnName("name").setValue("Dave")))
										.setFilters(List.of(new RowSelectionFilter().setIsSelected(true))))),
				// Update Example 4
				new UpdateExample().setDescription(
						"Set status = true only for rows with IDs r2 and r5 (explicit RowIdFilter targeting previously retrieved IDs).")
						.setUpdate_json(JDOSecondaryPropertyUtils.createJSONFromObject(
								new Update().setSet(List.of(new SetValue().setColumnName("status").setValue(true)))
										.setFilters(List.of(new RowIdFilter().setRowIdsIn(List.of("r2", "r5"))))))),
				writer);

		System.out.println(writerToString(writer));
	}
	
	static String writerToString(Writer writer) {
		return writer.toString().replaceAll("&quot;", "\"").replaceAll("&apos;", "'").replaceAll("&gt;", ">");
	}

	public static class QueryExamples {
		private QueryExample[] examples;

		public QueryExample[] getExamples() {
			return examples;
		}

		public QueryExamples setExamples(QueryExample... examples) {
			this.examples = examples;
			return this;
		}
	}

	public static class QueryExample {
		private String description;
		private String query_json;

		public String getDescription() {
			return description;
		}

		public QueryExample setDescription(String description) {
			this.description = description;
			return this;
		}

		public String getQuery_json() {
			return query_json;
		}

		public QueryExample setQuery_json(String query_json) {
			this.query_json = query_json;
			return this;
		}
	}

	public static class UpdateExamples {
		private UpdateExample[] examples;

		public UpdateExample[] getExamples() {
			return examples;
		}

		public UpdateExamples setExamples(UpdateExample... examples) {
			this.examples = examples;
			return this;
		}
	}

	public static class UpdateExample {
		private String description;
		private String update_json;

		public String getDescription() {
			return description;
		}

		public UpdateExample setDescription(String description) {
			this.description = description;
			return this;
		}

		public String getUpdate_json() {
			return update_json;
		}

		public UpdateExample setUpdate_json(String update_json) {
			this.update_json = update_json;
			return this;
		}
	}
}
