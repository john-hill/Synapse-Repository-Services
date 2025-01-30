package org.sagebionetworks.change.workers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

import org.sagebionetworks.util.ValidateArgument;

/**
 * Provides an iterator over a range of lower and upper bounds given a page size
 * and direction.
 */
public class RangeIterator implements Iterable<RangeIterator.Range> {

	private final Iterable<RangeIterator.Range> it;

	public RangeIterator(long minValue, long maxValue, long pageSize, Direction direction) {
		ValidateArgument.required(direction, "direction");
		ValidateArgument.requirement(maxValue >= minValue, "maxValue must be greater or equal to minValue");
		ValidateArgument.requirement(pageSize > 0, "pageSize must be greater than zero");
		int numberOfRanges = (int) (((maxValue - minValue) / pageSize) + 1);
		List<Range> list = new ArrayList<>(numberOfRanges);
		for (long lowerBounds = minValue; lowerBounds <= maxValue; lowerBounds += pageSize) {
			long upperBounds = (lowerBounds-1) + pageSize;
			list.add(new Range(lowerBounds, upperBounds));
		}
		if (Direction.reverse.equals(direction)) {
			Collections.reverse(list);
		}
		it = list;
	}

	@Override
	public Iterator<Range> iterator() {
		return this.it.iterator();
	}

	public enum Direction {
		forward, reverse
	};

	public static class Range {

		private final long lowerBounds;
		private final long upperBounds;

		Range(long lowerBounds, long upperBounds) {
			super();
			this.lowerBounds = lowerBounds;
			this.upperBounds = upperBounds;
		}

		public long getLowerBounds() {
			return lowerBounds;
		}

		public long getUpperBounds() {
			return upperBounds;
		}

		@Override
		public int hashCode() {
			return Objects.hash(lowerBounds, upperBounds);
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			Range other = (Range) obj;
			return lowerBounds == other.lowerBounds && upperBounds == other.upperBounds;
		}

		@Override
		public String toString() {
			return "Range [lowerBounds=" + lowerBounds + ", upperBounds=" + upperBounds + "]";
		}

	}
}
