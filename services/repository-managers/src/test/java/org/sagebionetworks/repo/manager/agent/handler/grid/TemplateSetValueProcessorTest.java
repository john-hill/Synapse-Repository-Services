package org.sagebionetworks.repo.manager.agent.handler.grid;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.RowData;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.RowObject;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.RowView;
import org.sagebionetworks.repo.model.grid.patch.ConType;
import org.sagebionetworks.repo.model.grid.patch.ConValue;
import org.sagebionetworks.repo.model.grid.update.TemplateSetValue;
import org.sagebionetworks.repo.model.jdo.JDOSecondaryPropertyUtils;

public class TemplateSetValueProcessorTest {

	private TemplateSetValueProcessor processor;
	private RowView row;

	@BeforeEach
	public void before() {
		processor = new TemplateSetValueProcessor();
		row = new RowView();
	}
	
	@Test
	public void testCreateConWithCombineExtractTransform() {
		TemplateSetValue sv = new TemplateSetValue().setColumnName("participantId").setSourceTemplate("{name}/{size}")
				.setPattern("participant-(\\d+)/(\\w+)").setReplacement("$2^$1");
		JSONObject svRaw = JDOSecondaryPropertyUtils.createJSONObjectForEntity(sv);
		row.setRowObject(new RowObject()
				.setData(new RowData().setRowJsonDocument(new JSONObject("{\"name\":\"participant-123\", \"size\":\"medium\"}"))));
		// call under test
		ConValue con = processor.createConValue(row, sv, svRaw);

		ConValue expected = new ConValue(ConType.STRING, "medium^123");
		assertEquals(expected, con);
	}

	@Test
	public void testCreateCon() {
		TemplateSetValue sv = new TemplateSetValue().setColumnName("participantId").setSourceTemplate("{name}")
				.setPattern("participant-(\\d+)").setReplacement("$1");
		JSONObject svRaw = JDOSecondaryPropertyUtils.createJSONObjectForEntity(sv);
		row.setRowObject(new RowObject()
				.setData(new RowData().setRowJsonDocument(new JSONObject("{\"name\":\"participant-123\"}"))));
		// call under test
		ConValue con = processor.createConValue(row, sv, svRaw);

		ConValue expected = new ConValue(ConType.LONG, 123L);
		assertEquals(expected, con);
	}
}
