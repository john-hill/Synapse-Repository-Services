package org.sagebionetworks.change.workers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.sagebionetworks.change.workers.RangeIterator.Direction;
import org.sagebionetworks.change.workers.RangeIterator.Range;

import com.google.common.collect.Lists;

public class RangeIteratorTest {

	@ParameterizedTest
	@EnumSource(Direction.class)
	public void testRangeIteratorWithAtPageSize(Direction dir) {

		long minValue = 100;
		long maxValue = 150;
		long pageSize = 10;

		// call under test
		List<Range> result = Lists.newArrayList(new RangeIterator(minValue, maxValue, pageSize, dir));
		List<Range> expected = List.of(new Range(100, 109), new Range(110, 119), new Range(120, 129),
				new Range(130, 139), new Range(140, 149), new Range(150, 159));
		if (Direction.reverse.equals(dir)) {
			expected = Lists.reverse(expected);
		}
		assertEquals(expected, result);
	}

	@ParameterizedTest
	@EnumSource(Direction.class)
	public void testRangeIteratorWithUnderPageSize(Direction dir) {

		long minValue = 100;
		long maxValue = 150;
		long pageSize = 9;

		// call under test
		List<Range> result = Lists.newArrayList(new RangeIterator(minValue, maxValue, pageSize, dir));
		List<Range> expected = List.of(new Range(100, 108), new Range(109, 117), new Range(118, 126),
				new Range(127, 135), new Range(136, 144), new Range(145, 153));
		if (Direction.reverse.equals(dir)) {
			expected = Lists.reverse(expected);
		}
		assertEquals(expected, result);
	}

	@ParameterizedTest
	@EnumSource(Direction.class)
	public void testRangeIteratorWithOverPageSize(Direction dir) {

		long minValue = 100;
		long maxValue = 150;
		long pageSize = 11;

		// call under test
		List<Range> result = Lists.newArrayList(new RangeIterator(minValue, maxValue, pageSize, dir));
		List<Range> expected = List.of(new Range(100, 110), new Range(111, 121), new Range(122, 132),
				new Range(133, 143), new Range(144, 154));
		if (Direction.reverse.equals(dir)) {
			expected = Lists.reverse(expected);
		}
		assertEquals(expected, result);
	}

	@ParameterizedTest
	@EnumSource(Direction.class)
	public void testRangeIteratorPageLargerThanSpan(Direction dir) {

		long minValue = 100;
		long maxValue = 110;
		long pageSize = 11;

		// call under test
		List<Range> result = Lists.newArrayList(new RangeIterator(minValue, maxValue, pageSize, dir));
		List<Range> expected = List.of(new Range(100, 110));
		if (Direction.reverse.equals(dir)) {
			expected = Lists.reverse(expected);
		}
		assertEquals(expected, result);
	}

	@Test
	public void testRangeIteratorWithNullDirection() {
		Direction dir = null;
		long minValue = 100;
		long maxValue = 110;
		long pageSize = 11;
		String message = assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			new RangeIterator(minValue, maxValue, pageSize, dir);
		}).getMessage();
		assertEquals("direction is required.", message);
	}

	@ParameterizedTest
	@EnumSource(Direction.class)
	public void testRangeIteratorWithMaxGreaterThanMinn(Direction dir) {
		long minValue = 100;
		long maxValue = 10;
		long pageSize = 11;
		String message = assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			new RangeIterator(minValue, maxValue, pageSize, dir);
		}).getMessage();
		assertEquals("maxValue must be greater or equal to minValue", message);
	}

	@ParameterizedTest
	@EnumSource(Direction.class)
	public void testRangeIteratorWithMaxEqualToMin(Direction dir) {

		long minValue = 100;
		long maxValue = 100;
		long pageSize = 11;

		// call under test
		List<Range> result = Lists.newArrayList(new RangeIterator(minValue, maxValue, pageSize, dir));
		List<Range> expected = List.of(new Range(100, 110));
		if (Direction.reverse.equals(dir)) {
			expected = Lists.reverse(expected);
		}
		assertEquals(expected, result);
	}

	@ParameterizedTest
	@EnumSource(Direction.class)
	public void testRangeIteratorWithNegativePageSize(Direction dir) {
		long minValue = 100;
		long maxValue = 110;
		long pageSize = -1;
		String message = assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			new RangeIterator(minValue, maxValue, pageSize, dir);
		}).getMessage();
		assertEquals("pageSize must be greater than zero", message);
	}

}
