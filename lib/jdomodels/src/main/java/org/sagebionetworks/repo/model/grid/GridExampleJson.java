package org.sagebionetworks.repo.model.grid;

import java.io.StringWriter;
import java.io.Writer;
import java.util.List;

import org.json.JSONArray;
import org.sagebionetworks.repo.model.UnmodifiableXStream;
import org.sagebionetworks.repo.model.grid.query.CellValueFilter;
import org.sagebionetworks.repo.model.grid.query.CellValueOperator;
import org.sagebionetworks.repo.model.grid.query.Query;
import org.sagebionetworks.repo.model.grid.query.QueryRequest;
import org.sagebionetworks.repo.model.grid.query.RowIdFilter;
import org.sagebionetworks.repo.model.grid.query.RowIsValidFilter;
import org.sagebionetworks.repo.model.grid.query.RowSelectionFilter;
import org.sagebionetworks.repo.model.grid.query.RowValidationResultFilter;
import org.sagebionetworks.repo.model.grid.query.SelectAll;
import org.sagebionetworks.repo.model.grid.query.SelectByName;
import org.sagebionetworks.repo.model.grid.query.SelectSelection;
import org.sagebionetworks.repo.model.grid.query.ValidationOperator;
import org.sagebionetworks.repo.model.grid.query.function.CountStar;
import org.sagebionetworks.repo.model.grid.update.GridUpdateRequest;
import org.sagebionetworks.repo.model.grid.update.LiteralSetValue;
import org.sagebionetworks.repo.model.grid.update.OnMatchFailure;
import org.sagebionetworks.repo.model.grid.update.RegexExtractSetValue;
import org.sagebionetworks.repo.model.grid.update.Update;
import org.sagebionetworks.repo.model.jdo.JDOSecondaryPropertyUtils;
import org.sagebionetworks.schema.adapter.JSONObjectAdapter;
import org.sagebionetworks.schema.adapter.JSONObjectAdapterException;

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
						.setQuery_json(JDOSecondaryPropertyUtils.createJSONFromObject(new QueryRequest()
								.setQuery(new Query().setColumnSelection(List.of(new SelectAll())).setLimit(10L)))),
				// Example 2
				new QueryExample()
						.setDescription(
								"Return up to 10 rows selecting the species and weight columns by name (no filters).")
						.setQuery_json(
								JDOSecondaryPropertyUtils
										.createJSONFromObject(
												new QueryRequest().setQuery(new Query()
														.setColumnSelection(
																List.of(new SelectByName().setColumnName("species"),
																		new SelectByName().setColumnName("weight")))
														.setLimit(10L)))),
				// Example 3
				new QueryExample()
						.setDescription("Return the total number of rows currently in the grid (single count value).")
						.setQuery_json(JDOSecondaryPropertyUtils.createJSONFromObject(new QueryRequest()
								.setQuery(new Query().setColumnSelection(List.of(new CountStar())).setLimit(1L)))),
				// Example 4
				new QueryExample().setDescription(
						"Return up to 50 rows selecting all columns where age > 25 AND JSON schema validation is invalid (isValid = false).")
						.setQuery_json(
								JDOSecondaryPropertyUtils
										.createJSONFromObject(
												new QueryRequest().setQuery(
														new Query().setColumnSelection(List.of(new SelectAll()))
																.setFilters(List
																		.of(new CellValueFilter().setColumnName("age")
																				.setOperator(
																						CellValueOperator.GREATER_THAN)
																				.setValue(25),
																				new RowIsValidFilter().setValue(false)))
																.setLimit(50L)))),
				// Example 5
				new QueryExample().setDescription(
						"Return up to 5 rows selecting all columns where color is one of \"red\", or \"green\".")
						.setQuery_json(JDOSecondaryPropertyUtils.createJSONFromObject(
								new QueryRequest().setQuery(new Query().setColumnSelection(List.of(new SelectAll()))
										.setFilters(List.of(new CellValueFilter().setColumnName("color")
												.setOperator(CellValueOperator.IN)
												.setValue(new JSONArray(List.of("red", "green")))))
										.setLimit(50L)))),
				// Example 6
				new QueryExample().setDescription(
						"Count the currently selected rows whose validation error message contains 'expected type' (SQL LIKE pattern using % wildcards).")
						.setQuery_json(JDOSecondaryPropertyUtils.createJSONFromObject(
								new QueryRequest().setQuery(new Query().setColumnSelection(List.of(new CountStar()))
										.setFilters(List.of(new RowSelectionFilter().setIsSelected(true),
												new RowValidationResultFilter().setOperator(ValidationOperator.LIKE)
														.setValidationResultValue("%expected type%")))
										.setLimit(1L)))),
				// Example 6
				new QueryExample()
						.setDescription(
								"Return up to 10 rows selecting only the columns currently selected by the user.")
						.setQuery_json(JDOSecondaryPropertyUtils.createJSONFromObject(new QueryRequest().setQuery(
								new Query().setColumnSelection(List.of(new SelectSelection())).setLimit(10L)))),
				// Example 7
				new QueryExample()
						.setDescription("Return up to 10 rows where subspecies is undefined and species is not null.")
						// Hard-coded JSON because our auto-generated models do not distinguish between
						// null and undefined
						.setQuery_json(JDOSecondaryPropertyUtils.createJSONFromObject(
								new QueryRequest().setQuery(new Query().setColumnSelection(List.of(new SelectAll()))
										.setFilters(List.of(
												new CellValueFilter().setColumnName("subspecies")
														.setOperator(CellValueOperator.IS_UNDEFINED),
												new CellValueFilter().setColumnName("species")
														.setOperator(CellValueOperator.IS_NOT_NULL)))
										.setLimit(10L))))),
				writer);

		System.out.println(writerToString(writer));

		writer = new StringWriter();
		X_STREAM.toXML(new UpdateExamples().setExamples(
				// Update Example 1
				new UpdateExample().setDescription("Set age = 25 for rows where age is currently null.").setUpdate_json(
						JDOSecondaryPropertyUtils.createJSONFromObject(new GridUpdateRequest().setUpdateBatch(List.of(
								new Update().setSet(List.of(new LiteralSetValue().setColumnName("age").setValue(25)))
										.setFilters(List.of(new CellValueFilter().setColumnName("age")
												.setOperator(CellValueOperator.IS_NULL))))))),
				// Update Example 2
				new UpdateExample().setDescription(
						"For rows where height > 12, set type = 'tall' and footing = null; cap updates at 10 rows.")
						.setUpdate_json(
								JDOSecondaryPropertyUtils
										.createJSONFromObject(new GridUpdateRequest()
												.setUpdateBatch(List.of(new Update()
														.setSet(List.of(
																new LiteralSetValue().setColumnName("type")
																		.setValue("tall"),
																new SetValueWithNull().setColumnName("footing")))
														.setFilters(
																List.of(new CellValueFilter().setColumnName("height")
																		.setOperator(CellValueOperator.GREATER_THAN)
																		.setValue(12)))
														.setLimit(10L))))),
				// Update Example 3
				new UpdateExample().setDescription("Set name = 'Dave' for all currently selected rows.")
						.setUpdate_json(JDOSecondaryPropertyUtils
								.createJSONFromObject(new GridUpdateRequest().setUpdateBatch(List.of(new Update()
										.setSet(List.of(new LiteralSetValue().setColumnName("name").setValue("Dave")))
										.setFilters(List.of(new RowSelectionFilter().setIsSelected(true))))))),
				// Update Example 4
				new UpdateExample().setDescription(
						"Set status = true only for rows with IDs r2 and r5 (explicit RowIdFilter targeting previously retrieved IDs).")
						.setUpdate_json(JDOSecondaryPropertyUtils
								.createJSONFromObject(new GridUpdateRequest().setUpdateBatch(List.of(new Update()
										.setSet(List.of(new LiteralSetValue().setColumnName("status").setValue(true)))
										.setFilters(List.of(new RowIdFilter().setRowIdsIn(List.of("r2", "r5")))))))),
				// Update Example 5
				new UpdateExample().setDescription("Set color to undefined for rows where material is null.")
						.setUpdate_json(JDOSecondaryPropertyUtils
								.createJSONFromObject(new GridUpdateRequest().setUpdateBatch(List
										.of(new Update().setSet(List.of(new LiteralSetValue().setColumnName("color")))
												.setFilters(List.of(new CellValueFilter().setColumnName("material")
														.setOperator(CellValueOperator.IS_NULL))))))),
				// Update Example 6
				new UpdateExample().setDescription(
						"Batch update: (1) Set status = 'active' where age > 18, (2) Set category = 'senior' where age >= 65, (3) Set discount = 0.15 for all selected rows.")
						.setUpdate_json(JDOSecondaryPropertyUtils
								.createJSONFromObject(new GridUpdateRequest().setUpdateBatch(List.of(
										// 1
										new Update()
												.setSet(List.of(new LiteralSetValue().setColumnName("status")
														.setValue("active")))
												.setFilters(List.of(new CellValueFilter().setColumnName("age")
														.setOperator(CellValueOperator.GREATER_THAN).setValue(18))),
										// 2
										new Update()
												.setSet(List.of(new LiteralSetValue().setColumnName("category")
														.setValue("senior")))
												.setFilters(List.of(new CellValueFilter().setColumnName("age")
														.setOperator(CellValueOperator.GREATER_THAN_OR_EQUALS)
														.setValue(65))),
										// 3
										new Update()
												.setSet(List.of(
														new LiteralSetValue().setColumnName("discount").setValue(0.15)))
												.setFilters(List.of(new RowSelectionFilter().setIsSelected(true))))))),
				// Update Example 7
				new UpdateExample().setDescription(
						"Extract participant ID from fileName column using regex pattern 'participant-(\\d+)' and set it to the participantId column. If the pattern doesn't match, set participantId to null.")
						.setUpdate_json(JDOSecondaryPropertyUtils
								.createJSONFromObject(new GridUpdateRequest().setUpdateBatch(List.of(new Update()
										.setSet(List.of(new RegexExtractSetValue().setColumnName("participantId")
												.setSourceColumnName("fileName").setPattern("participant-(\\d+)")
												.setGroupIndex(1L).setOnMatchFailure(OnMatchFailure.SET_NULL)))
										.setFilters(List.of(new CellValueFilter().setColumnName("fileName")
												.setOperator(CellValueOperator.IS_NOT_NULL)))))))

		// end
		), writer);

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

	private static class SetValueWithNull extends LiteralSetValue {

		@Override
		public JSONObjectAdapter writeToJSONObject(JSONObjectAdapter writeTo) throws JSONObjectAdapterException {
			JSONObjectAdapter a = super.writeToJSONObject(writeTo);
			if (this.getValue() == null) {
				writeTo.putNull("value");
			}
			return a;
		}

	}

}
