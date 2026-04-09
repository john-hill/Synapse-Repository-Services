package org.sagebionetworks.repo.manager.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class SearchIndexValidatorTest {

	private SearchIndexValidator validator;

	@BeforeEach
	public void setUp() {
		validator = new SearchIndexValidator();
	}

	@Test
	public void testValidateDefiningSQLWithValidSingleEntity() {
		// call under test
		validator.validateDefiningSQL("SELECT * FROM syn123");
	}

	@Test
	public void testValidateDefiningSQLWithSelectedColumns() {
		// call under test
		validator.validateDefiningSQL("SELECT foo, bar FROM syn456");
	}

	@Test
	public void testValidateDefiningSQLWithMultiEntityJoin() {
		assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			validator.validateDefiningSQL("SELECT a.x, b.y FROM syn123 a JOIN syn456 b ON a.id = b.id");
		});
	}

	@Test
	public void testValidateDefiningSQLWithNull() {
		assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			validator.validateDefiningSQL(null);
		});
	}

	@Test
	public void testValidateDefiningSQLWithBlank() {
		assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			validator.validateDefiningSQL("   ");
		});
	}

	@Test
	public void testValidateDefiningSQLWithEmptyString() {
		assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			validator.validateDefiningSQL("");
		});
	}

	@ParameterizedTest(name = "SQL with whitespace/casing: {0}")
	@ValueSource(strings = {
		"  SELECT * FROM syn123  ",
		"select * from syn123",
		"SELECT  *  FROM  syn123",
		"Select studyName From syn123"
	})
	public void testValidateDefiningSQLWithWhitespaceAndCasingVariations(String sql) {
		// call under test
		validator.validateDefiningSQL(sql);
	}

	@Test
	public void testValidateDefiningSQLWithWhereClause() {
		// call under test
		validator.validateDefiningSQL("SELECT studyName FROM syn123 WHERE status = 'Active'");
	}
}
