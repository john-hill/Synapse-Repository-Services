package org.sagebionetworks.repo.manager.agent.handler.grid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.RowData;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.RowObject;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.RowView;
import org.sagebionetworks.repo.model.grid.patch.ConType;
import org.sagebionetworks.repo.model.grid.patch.ConValue;
import org.sagebionetworks.repo.model.grid.update.OnMatchFailure;
import org.sagebionetworks.repo.model.grid.update.RegexExtractSetValue;
import org.sagebionetworks.repo.model.jdo.JDOSecondaryPropertyUtils;

public class RegexSetValueProcessorTest {

	private RegexSetValueProcessor processor;
	private RowView row;

	@BeforeEach
	public void before() {
		processor = new RegexSetValueProcessor();
		row = new RowView();
	}

	@Test
	public void testCreateCon() {
		RegexExtractSetValue sv = new RegexExtractSetValue().setColumnName("participantId")
				.setPattern("participant-(\\d+)").setSourceColumnName("name").setGroupIndex(1L);
		JSONObject svRaw = JDOSecondaryPropertyUtils.createJSONObjectForEntity(sv);
		row.setRowObject(new RowObject()
				.setData(new RowData().setRowJsonDocument(new JSONObject("{\"name\":\"participant-123\"}"))));
		// call under test
		ConValue con = processor.createConValue(row, sv, svRaw);

		ConValue expected = new ConValue(ConType.LONG, 123L);
		assertEquals(expected, con);
	}

	@Test
	public void testCreateConWithMultipleGroupsExtractSecondGroup() {
		RegexExtractSetValue sv = new RegexExtractSetValue().setColumnName("participantId")
				.setPattern("participant-(\\d+)-(\\w+)-(\\d+)").setSourceColumnName("name").setGroupIndex(2L);
		JSONObject svRaw = JDOSecondaryPropertyUtils.createJSONObjectForEntity(sv);
		row.setRowObject(new RowObject()
				.setData(new RowData().setRowJsonDocument(new JSONObject("{\"name\":\"participant-123-abc-456\"}"))));
		// call under test
		ConValue con = processor.createConValue(row, sv, svRaw);

		ConValue expected = new ConValue(ConType.STRING, "abc");
		assertEquals(expected, con);
	}

	@Test
	public void testCreateConWithDoubleExtraction() {
		RegexExtractSetValue sv = new RegexExtractSetValue().setColumnName("measurement")
				.setPattern("value-(\\d+\\.\\d+)").setSourceColumnName("name").setGroupIndex(1L);
		JSONObject svRaw = JDOSecondaryPropertyUtils.createJSONObjectForEntity(sv);
		row.setRowObject(new RowObject()
				.setData(new RowData().setRowJsonDocument(new JSONObject("{\"name\":\"value-123.45\"}"))));
		// call under test
		ConValue con = processor.createConValue(row, sv, svRaw);

		ConValue expected = new ConValue(ConType.DOUBLE, 123.45);
		assertEquals(expected, con);
	}

	@Test
	public void testCreateConWithNoMatchAndSetNull() {
		RegexExtractSetValue sv = new RegexExtractSetValue().setColumnName("participantId")
				.setPattern("participant-(\\d+)").setSourceColumnName("name").setGroupIndex(1L)
				.setOnMatchFailure(OnMatchFailure.SET_NULL);
		JSONObject svRaw = JDOSecondaryPropertyUtils.createJSONObjectForEntity(sv);
		row.setRowObject(
				new RowObject().setData(new RowData().setRowJsonDocument(new JSONObject("{\"name\":\"user-456\"}"))));
		// call under test
		ConValue con = processor.createConValue(row, sv, svRaw);

		ConValue expected = new ConValue(ConType.NULL, null);
		assertEquals(expected, con);
	}

	@Test
	public void testCreateConWithNoMatchAndSkipUpdate() {
		RegexExtractSetValue sv = new RegexExtractSetValue().setColumnName("participantId")
				.setPattern("participant-(\\d+)").setSourceColumnName("name").setGroupIndex(1L)
				.setOnMatchFailure(OnMatchFailure.SKIP_UPDATE);
		JSONObject svRaw = JDOSecondaryPropertyUtils.createJSONObjectForEntity(sv);
		row.setRowObject(new RowObject().setData(new RowData()
				.setRowJsonDocument(new JSONObject("{\"name\":\"user-456\", \"participantId\":\"current value\"}"))));
		// call under test
		ConValue con = processor.createConValue(row, sv, svRaw);

		ConValue expected = new ConValue(ConType.STRING, "current value");
		assertEquals(expected, con);
	}

	@Test
	public void testCreateConWithNoMatchAndSkipUpdateAndCurrentUndefined() {
		RegexExtractSetValue sv = new RegexExtractSetValue().setColumnName("participantId")
				.setPattern("participant-(\\d+)").setSourceColumnName("name").setGroupIndex(1L)
				.setOnMatchFailure(OnMatchFailure.SKIP_UPDATE);
		JSONObject svRaw = JDOSecondaryPropertyUtils.createJSONObjectForEntity(sv);
		row.setRowObject(
				new RowObject().setData(new RowData().setRowJsonDocument(new JSONObject("{\"name\":\"user-456\"}"))));
		// call under test
		ConValue con = processor.createConValue(row, sv, svRaw);

		ConValue expected = new ConValue(ConType.UNDEFINED, null);
		assertEquals(expected, con);
	}

	@Test
	public void testCreateConWithNoMatchAndSkipUpdateAndCurrentNull() {
		RegexExtractSetValue sv = new RegexExtractSetValue().setColumnName("participantId")
				.setPattern("participant-(\\d+)").setSourceColumnName("name").setGroupIndex(1L)
				.setOnMatchFailure(OnMatchFailure.SKIP_UPDATE);
		JSONObject svRaw = JDOSecondaryPropertyUtils.createJSONObjectForEntity(sv);
		row.setRowObject(new RowObject().setData(
				new RowData().setRowJsonDocument(new JSONObject("{\"name\":\"user-456\", \"participantId\":null}"))));
		// call under test
		ConValue con = processor.createConValue(row, sv, svRaw);

		ConValue expected = new ConValue(ConType.NULL, null);
		assertEquals(expected, con);
	}

	@Test
	public void testCreateConWithSourceNullAndSetNull() {
		RegexExtractSetValue sv = new RegexExtractSetValue().setColumnName("participantId")
				.setPattern("participant-(\\d+)").setSourceColumnName("name").setGroupIndex(1L)
				.setOnMatchFailure(OnMatchFailure.SET_NULL);
		JSONObject svRaw = JDOSecondaryPropertyUtils.createJSONObjectForEntity(sv);
		row.setRowObject(new RowObject()
				.setData(new RowData().setRowJsonDocument(new JSONObject("{\"participantId\":\"current value\"}"))));
		// call under test
		ConValue con = processor.createConValue(row, sv, svRaw);

		ConValue expected = new ConValue(ConType.NULL, null);
		assertEquals(expected, con);
	}

	@Test
	public void testCreateConWithSourceNullAndSkipUpdate() {
		RegexExtractSetValue sv = new RegexExtractSetValue().setColumnName("participantId")
				.setPattern("participant-(\\d+)").setSourceColumnName("name").setGroupIndex(1L)
				.setOnMatchFailure(OnMatchFailure.SKIP_UPDATE);
		JSONObject svRaw = JDOSecondaryPropertyUtils.createJSONObjectForEntity(sv);
		row.setRowObject(new RowObject()
				.setData(new RowData().setRowJsonDocument(new JSONObject("{\"participantId\":\"current value\"}"))));
		// call under test
		ConValue con = processor.createConValue(row, sv, svRaw);

		ConValue expected = new ConValue(ConType.STRING, "current value");
		assertEquals(expected, con);
	}

	@Test
	public void testCreateConWithNullRow() {
		RegexExtractSetValue sv = new RegexExtractSetValue().setColumnName("participantId")
				.setPattern("participant-(\\d+)").setSourceColumnName("name").setGroupIndex(1L);
		JSONObject svRaw = JDOSecondaryPropertyUtils.createJSONObjectForEntity(sv);

		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			processor.createConValue(null, sv, svRaw);
		});

		assertEquals("row is required.", exception.getMessage());
	}

	@Test
	public void testCreateConWithNullSetValue() {
		row.setRowObject(new RowObject()
				.setData(new RowData().setRowJsonDocument(new JSONObject("{\"name\":\"participant-123\"}"))));
		JSONObject svRaw = new JSONObject();

		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
			processor.createConValue(row, null, svRaw);
		});

		assertEquals("RegexExtractSetValue is required.", exception.getMessage());
	}

	@Test
	public void testCreateConWithNullColumnName() {
		RegexExtractSetValue sv = new RegexExtractSetValue().setColumnName(null).setPattern("participant-(\\d+)")
				.setSourceColumnName("name").setGroupIndex(1L);
		JSONObject svRaw = JDOSecondaryPropertyUtils.createJSONObjectForEntity(sv);
		row.setRowObject(new RowObject()
				.setData(new RowData().setRowJsonDocument(new JSONObject("{\"name\":\"participant-123\"}"))));

		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
			processor.createConValue(row, sv, svRaw);
		});

		assertEquals("RegexExtractSetValue.columnName is required.", exception.getMessage());
	}

	@Test
	public void testCreateConWithNullGroupIndex() {
		RegexExtractSetValue sv = new RegexExtractSetValue().setGroupIndex(null).setColumnName("participantId")
				.setPattern("participant-(\\d+)").setSourceColumnName("name");
		JSONObject svRaw = JDOSecondaryPropertyUtils.createJSONObjectForEntity(sv);
		row.setRowObject(new RowObject()
				.setData(new RowData().setRowJsonDocument(new JSONObject("{\"name\":\"participant-123\"}"))));

		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
			processor.createConValue(row, sv, svRaw);
		});

		assertEquals("RegexExtractSetValue.groupIndex is required.", exception.getMessage());
	}

	@Test
	public void testCreateConWithNullPattern() {
		RegexExtractSetValue sv = new RegexExtractSetValue().setPattern(null).setColumnName("participantId")
				.setSourceColumnName("name").setGroupIndex(1L);
		JSONObject svRaw = JDOSecondaryPropertyUtils.createJSONObjectForEntity(sv);
		row.setRowObject(new RowObject()
				.setData(new RowData().setRowJsonDocument(new JSONObject("{\"name\":\"participant-123\"}"))));

		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
			processor.createConValue(row, sv, svRaw);
		});

		assertEquals("RegexExtractSetValue.pattern is required.", exception.getMessage());
	}

	@Test
	public void testCreateConWithNullSourceColumnName() {
		RegexExtractSetValue sv = new RegexExtractSetValue().setSourceColumnName(null).setColumnName("participantId")
				.setPattern("participant-(\\d+)").setGroupIndex(1L);
		JSONObject svRaw = JDOSecondaryPropertyUtils.createJSONObjectForEntity(sv);
		row.setRowObject(new RowObject()
				.setData(new RowData().setRowJsonDocument(new JSONObject("{\"name\":\"participant-123\"}"))));

		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
			processor.createConValue(row, sv, svRaw);
		});

		assertEquals("RegexExtractSetValue.sourceColumnName is required.", exception.getMessage());
	}

	@Test
	public void testCreateConWithNullRawSetValue() {
		RegexExtractSetValue sv = new RegexExtractSetValue().setColumnName("participantId")
				.setPattern("participant-(\\d+)").setSourceColumnName("name").setGroupIndex(1L);
		row.setRowObject(new RowObject()
				.setData(new RowData().setRowJsonDocument(new JSONObject("{\"name\":\"participant-123\"}"))));

		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
			processor.createConValue(row, sv, null);
		});

		assertEquals("rawSetValue is required.", exception.getMessage());
	}

}
