package org.sagebionetworks.table.query.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

public class SampleBuilderTest {

	@Test
	public void testBuild() {
		SampleBuilder sample = SampleBuilder.builder().firstName("john").lastName("hill").build();
		SampleBuilder two = sample.toBuilder().firstName("two").build();
		System.out.println(sample);
		System.out.println(two);
	}

	@Test
	public void testBuildWithNull() {
		String message = assertThrows(IllegalArgumentException.class, () -> {
			SampleBuilder.builder().firstName(null).build();
		}).getMessage();
		assertEquals("firstName is marked non-null but is null", message);
	}

	@Test
	public void testSubBuilder() {
		SampleBuilder sample = SampleBuilder.builder().firstName("one").lastName("two")
				.subBuilder(SubBuilder.builder().aLong(123L).aString("some string").build()).build();
		
		SampleBuilder clone = sample.toBuilder().subBuilder(sample.getSubBuilder().toBuilder().aLong(345L).build()).build();
		System.out.println(clone);
	}

}
