package org.sagebionetworks.repo.manager.grid.internal.replica.view.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.Column;

public class MultiValuesViewFilterTest {

	private MultiValuesViewFilter filter;
	
	@BeforeEach
	public void before() {
		filter = new MultiValuesViewFilter(List.of(
			new Column().setName("a").setVectorIndex(0),
			new Column().setName("b").setVectorIndex(1)
		), List.of(
			new Object[] { "a1", 1 },
			new Object[] { "a2", 2 }
		));
	}
	
	@Test
	public void testGetConditions() {
		assertEquals("(V1.VEC_VAL->>'$.c0.v',V1.VEC_VAL->>'$.c1.v') IN (:cellValues0)", filter.getConditionSql(0));
		assertEquals("(V1.VEC_VAL->>'$.c0.v',V1.VEC_VAL->>'$.c1.v') IN (:cellValues2)", filter.getConditionSql(2));
	}
	
	@Test
	public void testGetParameterKey() {
		assertEquals("cellValues1", filter.getParameterKey(1));
		assertEquals("cellValues2", filter.getParameterKey(2));
	}

	@Test
	public void testGetParameterValue() {
		List<Object[]> result = (List<Object[]>) filter.getParameterValue();
		
		List<List<Object>> resultAsLists = result.stream().map(Arrays::asList).collect(Collectors.toList());

		List<List<Object>> expected = List.of(List.of("a1", 1), List.of("a2", 2));

		assertEquals(expected, resultAsLists);
	}

}
