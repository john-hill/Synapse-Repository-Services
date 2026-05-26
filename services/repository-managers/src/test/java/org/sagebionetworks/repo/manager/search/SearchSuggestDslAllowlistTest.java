package org.sagebionetworks.repo.manager.search;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

public class SearchSuggestDslAllowlistTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static void validate(String dsl) {
		try {
			SearchSuggestDslAllowlist.validate(MAPPER.readTree(dsl));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@Test
	public void testValidateWithAllowedTermSuggester() {
		// call under test
		assertDoesNotThrow(() -> validate(
				"{\"did_you_mean\":{\"text\":\"amiloid\",\"term\":{\"field\":\"title\"}}}"));
	}

	@Test
	public void testValidateWithTopLevelTextAndPhrase() {
		// call under test
		assertDoesNotThrow(() -> validate(
				"{\"text\":\"amiloid plak\",\"suggestion\":{\"phrase\":{\"field\":\"title\"}}}"));
	}

	@Test
	public void testValidateWithCompletionSuggester() {
		// call under test
		assertDoesNotThrow(() -> validate(
				"{\"complete\":{\"prefix\":\"amy\",\"completion\":{\"field\":\"title_suggest\"}}}"));
	}

	@Test
	public void testValidateWithDisallowedSuggesterRejected() {
		// call under test
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> validate(
				"{\"x\":{\"context\":{\"field\":\"title\"}}}"));
		assertTrue(e.getMessage().contains("not allowed"));
	}

	@Test
	public void testValidateWithEmbeddedScriptRejected() {
		// phrase collate can carry a Painless script — must be rejected. call under test
		assertThrows(IllegalArgumentException.class, () -> validate(
				"{\"s\":{\"phrase\":{\"field\":\"title\",\"collate\":{\"query\":{\"script\":\"x\"}}}}}"));
	}

	@Test
	public void testValidateWithNoSuggesterTypeRejected() {
		// call under test
		assertThrows(IllegalArgumentException.class, () -> validate("{\"s\":{\"text\":\"x\"}}"));
	}

	@Test
	public void testValidateWithNonObjectRejected() {
		// call under test
		assertThrows(IllegalArgumentException.class, () -> validate("\"not an object\""));
	}
}
