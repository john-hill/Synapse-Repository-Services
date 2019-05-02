package org.sagebionetworks.repo.model.entity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.sagebionetworks.repo.model.entity.IdAndVersionParser.ParseException;


public class EntityParserTest {
	
	@Test
	public void testAllParts() {
		// call under test
		IdAndVersion id = IdAndVersionParser.parseEntityId("syn123.456");
		assertNotNull(id);
		assertEquals(new Long(123), id.getId());
		assertEquals(new Long(456), id.getVersion());
	}
	
	@Test
	public void testUppderSyn() {
		// call under test
		IdAndVersion id = IdAndVersionParser.parseEntityId("SYN123.456");
		assertNotNull(id);
		assertEquals(new Long(123), id.getId());
		assertEquals(new Long(456), id.getVersion());
	}
	
	@Test
	public void testMixedCase() {
		// call under test
		IdAndVersion id = IdAndVersionParser.parseEntityId("sYn123.456");
		assertNotNull(id);
		assertEquals(new Long(123), id.getId());
		assertEquals(new Long(456), id.getVersion());
	}
	
	@Test
	public void testJustIdAllDigits() {
		// call under test
		IdAndVersion id = IdAndVersionParser.parseEntityId("1234567890");
		assertNotNull(id);
		assertEquals(new Long(1234567890), id.getId());
		assertEquals(null, id.getVersion());
	}

	@Test
	public void testSingleDigit() {
		// call under test
		IdAndVersion id = IdAndVersionParser.parseEntityId("1");
		assertNotNull(id);
		assertEquals(new Long(1), id.getId());
		assertEquals(null, id.getVersion());
	}
	
	@Test
	public void testNoSyn() {
		// call under test
		IdAndVersion id = IdAndVersionParser.parseEntityId("7890.456");
		assertNotNull(id);
		assertEquals(new Long(7890), id.getId());
		assertEquals(new Long(456), id.getVersion());
	}
	
	@Test
	public void testWhiteSpace() {
		// call under test
		IdAndVersion id = IdAndVersionParser.parseEntityId(" \t\nsyn123.456\n\t ");
		assertNotNull(id);
		assertEquals(new Long(123), id.getId());
		assertEquals(new Long(456), id.getVersion());
	}
	
	@Test (expected=IllegalArgumentException.class)
	public void testNullString() {
		// call under test
		IdAndVersionParser.parseEntityId(null);
	}
	
	@Test (expected=IllegalArgumentException.class)
	public void testEmpty() {
		// call under test
		IdAndVersionParser.parseEntityId("");
	}
	
	@Test
	public void testMissingY() {
		try {
			// call under test
			IdAndVersionParser.parseEntityId("sny123");
			fail();
		}catch(IllegalArgumentException e) {
			assertTrue(e.getCause() instanceof ParseException);
			assertEquals(1, ((ParseException)e.getCause()).getErrorIndex());
		}
	}
	
	@Test
	public void testMissingN() {
		try {
			// call under test
			IdAndVersionParser.parseEntityId("syy123");
			fail();
		}catch(IllegalArgumentException e) {
			assertTrue(e.getCause() instanceof ParseException);
			assertEquals(2, ((ParseException)e.getCause()).getErrorIndex());
		}
	}
	
	@Test
	public void testNonDigitsBelow() {
		try {
			// call under test
			IdAndVersionParser.parseEntityId("syn123/456");
			fail();
		}catch(IllegalArgumentException e) {
			assertTrue(e.getCause() instanceof ParseException);
			assertEquals(6, ((ParseException)e.getCause()).getErrorIndex());
		}
	}
	@Test
	public void testNonDigitsAbove() {
		try {
			// call under test
			IdAndVersionParser.parseEntityId("syn123:456");
			fail();
		}catch(IllegalArgumentException e) {
			assertTrue(e.getCause() instanceof ParseException);
			assertEquals(6, ((ParseException)e.getCause()).getErrorIndex());
		}
	}
	
	@Test
	public void testTrailingJunck() {
		try {
			// call under test
			IdAndVersionParser.parseEntityId("syn123.456 foo");
			fail();
		}catch(IllegalArgumentException e) {
			assertTrue(e.getCause() instanceof ParseException);
			assertEquals(11, ((ParseException)e.getCause()).getErrorIndex());
		}
	}
	
	@Test
	public void testMissingId() {
		try {
			// call under test
			IdAndVersionParser.parseEntityId("syn.456");
			fail();
		}catch(IllegalArgumentException e) {
			assertTrue(e.getCause() instanceof ParseException);
			assertEquals(3, ((ParseException)e.getCause()).getErrorIndex());
		}
	}
	
	@Test
	public void testMissingVersion() {
		try {
			// call under test
			IdAndVersionParser.parseEntityId("syn0.");
			fail();
		}catch(IllegalArgumentException e) {
			assertTrue(e.getCause() instanceof ParseException);
			assertEquals(5, ((ParseException)e.getCause()).getErrorIndex());
		}
	}

}
