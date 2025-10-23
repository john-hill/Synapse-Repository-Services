package org.sagebionetworks.repo.model.grid;

import java.io.StringWriter;
import java.util.List;

import org.sagebionetworks.repo.model.UnmodifiableXStream;
import org.sagebionetworks.repo.model.grid.query.CellValueFilter;
import org.sagebionetworks.repo.model.grid.query.CellValueOperator;
import org.sagebionetworks.repo.model.grid.query.Query;
import org.sagebionetworks.repo.model.grid.query.RowIsValidFilter;
import org.sagebionetworks.repo.model.grid.query.RowSelectionFilter;
import org.sagebionetworks.repo.model.grid.query.RowValidationResultFilter;
import org.sagebionetworks.repo.model.grid.query.SelectAll;
import org.sagebionetworks.repo.model.grid.query.ValidationOperator;
import org.sagebionetworks.repo.model.grid.query.function.CountStar;
import org.sagebionetworks.repo.model.grid.update.SetValue;
import org.sagebionetworks.repo.model.grid.update.Update;
import org.sagebionetworks.repo.model.jdo.JDOSecondaryPropertyUtils;

/**
 * This utility is used to prepare examples of the grid Query JSON for the grid
 * agent's instructions.
 */
public class GridExampleJson {

	public static void main(String[] args) {
		UnmodifiableXStream X_STREAM = UnmodifiableXStream.builder().allowTypes(QueryExamples.class, QueryExample.class)
				.alias("query_examples", QueryExamples.class).alias("query_example", QueryExample.class)
				.alias("update_examples", UpdateExamples.class).alias("update_example", UpdateExample.class).build();
		StringWriter writer = new StringWriter();
		X_STREAM.toXML(new QueryExamples().setExamples(
				//
				new QueryExample().setDescription("Select all columns and all rows with a limit of 10")
						.setQuery_json(JDOSecondaryPropertyUtils.createJSONFromObject(
								new Query().setColumnSelection(List.of(new SelectAll())).setLimit(10L))),
				//
				new QueryExample().setDescription("Count the number for rows that are in the grid")
						.setQuery_json(JDOSecondaryPropertyUtils.createJSONFromObject(
								new Query().setColumnSelection(List.of(new CountStar())).setLimit(1L))),
				//
				new QueryExample().setDescription(
						"Select all columns for rows where age is greater than 25 that also have invalid JSON schema validation results where 'isValid' is false.")
						.setQuery_json(JDOSecondaryPropertyUtils
								.createJSONFromObject(new Query().setColumnSelection(List.of(new SelectAll()))
										.setFilters(List.of(new CellValueFilter()
												.setColumnName("age").setOperator(CellValueOperator.GREATER_THAN)
												.setValue(List.of(25)), new RowIsValidFilter().setValue(false)))
										.setLimit(50L))),
				new QueryExample().setDescription(
						"Count the number of rows from the user's currently selected rows that have a JSON schema type validation error message containing the the phrase :'expected type'")
						.setQuery_json(JDOSecondaryPropertyUtils
								.createJSONFromObject(new Query().setColumnSelection(List.of(new CountStar()))
										.setFilters(List.of(new RowSelectionFilter().setIsSelected(true),
												new RowValidationResultFilter().setOperator(ValidationOperator.LIKE)
														.setValidationResultValue("%expected type%")))
										.setLimit(1L)))
		//
		//
		), writer);
		System.out.println(writer.toString().replaceAll("&quot;", "\"").replaceAll("&apos;", "'"));

		writer = new StringWriter();
		X_STREAM.toXML(new UpdateExamples().setExamples(
				//
				new UpdateExample()
						.setDescription(
								"For each row where the value for the 'age' column is null, set a default value of 25.")
						.setUpdate_json(JDOSecondaryPropertyUtils.createJSONFromObject(
								new Update().setSet(List.of(new SetValue().setColumnName("age").setValue(25)))
										.setFilters(List.of(new CellValueFilter().setColumnName("age")
												.setOperator(CellValueOperator.IS_NULL))))),
				//
				new UpdateExample().setDescription(
						"For each row where the 'height' column is greater than 12, set the 'type' to be 'tall' and 'footing' to be null.  Add a limit of 10 to ensure that no more than 10 rows are updated.")
						.setUpdate_json(JDOSecondaryPropertyUtils.createJSONFromObject(new Update()
								.setSet(List.of(new SetValue().setColumnName("type").setValue("tall"),
										new SetValue().setColumnName("footing").setValue(null)))
								.setFilters(List.of(new CellValueFilter().setColumnName("height")
										.setOperator(CellValueOperator.GREATER_THAN).setValue(List.of(12))))
								.setLimit(10L))),
				//
				new UpdateExample().setDescription(
						"For each row that the user currently has selected, set the value of the 'name' column to be 'Dave'.")
						.setUpdate_json(JDOSecondaryPropertyUtils.createJSONFromObject(
								new Update().setSet(List.of(new SetValue().setColumnName("name").setValue("Dave")))
										.setFilters(List.of(new RowSelectionFilter().setIsSelected(true)))))

		), writer);

		System.out.println(writer.toString().replaceAll("&quot;", "\"").replaceAll("&apos;", "'"));

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
