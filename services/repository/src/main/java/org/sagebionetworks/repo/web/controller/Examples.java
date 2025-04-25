package org.sagebionetworks.repo.web.controller;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.json.JSONException;
import org.json.JSONObject;
import org.sagebionetworks.repo.model.grid.CellId;
import org.sagebionetworks.repo.model.grid.crdt.CellLww;
import org.sagebionetworks.repo.model.grid.crdt.HistoryItem;
import org.sagebionetworks.repo.model.grid.crdt.HistoryItemRef;
import org.sagebionetworks.schema.adapter.JSONEntity;
import org.sagebionetworks.schema.adapter.JSONObjectAdapterException;
import org.sagebionetworks.schema.adapter.org.json.EntityFactory;

public class Examples {

	public static void main(String[] args) {

		CellLww cell = new CellLww().setCellId(new CellId().setRowId("syn88").setColumnName("age"))
				.setChangeHistory(List.of(new HistoryItem().setCellValue("12").setChangedById("A").setChangedOn(time(0))
						.setGridVersion(0L).setItemUUID(UUID.randomUUID().toString())));
		cell.setCalculatedValue("12");

		print(cell);

		CellLww clientOne = append(cell, new HistoryItem().setCellValue("11").setChangedById("B").setChangedOn(time(2))
				.setGridVersion(1L).setItemUUID(UUID.randomUUID().toString()));

		print(clientOne);

		CellLww clientTwo = append(cell, new HistoryItem().setCellValue("10").setChangedById("C").setChangedOn(time(1))
				.setGridVersion(1L).setItemUUID(UUID.randomUUID().toString()));

		print(clientTwo);

		CellLww merCellLww = merge(clientOne, clientTwo);
		print(merCellLww);
		
		HistoryItem last = merCellLww.getChangeHistory().get(merCellLww.getChangeHistory().size()-1);
		
		merCellLww.setUndo(List.of(new HistoryItemRef().setItemUUID(last.getItemUUID()).setCreatedOn(time(3))));
		merCellLww.setCalculatedValue("10");
		
		print(merCellLww);
		
		merCellLww.setRedo(List.of(new HistoryItemRef().setItemUUID(last.getItemUUID()).setCreatedOn(time(4))));
		merCellLww.setCalculatedValue("11");
		
		print(merCellLww);

	}

	public static CellLww append(CellLww cell, HistoryItem toAppend) {
		CellLww clone = clone(cell);
		clone.getChangeHistory().add(toAppend);
		clone.setCalculatedValue(toAppend.getCellValue());
		return clone;
	}

	public static void print(JSONEntity toPrint) {
		System.out.println();
		try {
			System.out.println(new JSONObject(EntityFactory.createJSONStringForEntity(toPrint)).toString(5));
		} catch (JSONException | JSONObjectAdapterException e) {
			throw new RuntimeException(e);
		}
		System.out.println();
	}

	public static Date time(int t) {
		Instant inst1 = Instant.parse("2025-04-01T07:00:00Z");
		return new Date(inst1.plus(t, ChronoUnit.SECONDS).toEpochMilli());
	}

	public static <T extends JSONEntity> T clone(T toClone) {
		try {
			return (T) EntityFactory.createEntityFromJSONString(EntityFactory.createJSONStringForEntity(toClone),
					toClone.getClass());
		} catch (JSONObjectAdapterException e) {
			throw new RuntimeException(e);
		}
	}

	public static CellLww merge(CellLww one, CellLww two) {
		CellLww r = new CellLww().setCellId(one.getCellId());

		Set<HistoryItem> set = new HashSet<>();
		set.addAll(one.getChangeHistory());
		set.addAll(two.getChangeHistory());
		List<HistoryItem> merged = set.stream().collect(Collectors.toList());
		sortHistory(merged);
		r.setChangeHistory(merged);
		r.setCalculatedValue(merged.get(merged.size() - 1).getCellValue());
		return r;
	}

	public static void sortHistory(List<HistoryItem> his) {
		Collections.sort(his, new Comparator<HistoryItem>() {
			@Override
			public int compare(HistoryItem h1, HistoryItem h2) {
				return Long.compare(h1.getChangedOn().getTime(), h2.getChangedOn().getTime());
			}
		});
	}
}
