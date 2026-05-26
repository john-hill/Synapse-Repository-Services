package org.sagebionetworks.repo.manager.search;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class SearchQueryDslAllowlistTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static JsonNode json(String s) {
		try {
			return MAPPER.readTree(s);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void validate(String dsl) {
		SearchQueryDslAllowlist.validate(json(dsl));
	}

	@Test
	public void testValidateWithAllowedLeafMatch() {
		// call under test
		assertDoesNotThrow(() -> validate("{\"match\":{\"abstract\":\"amyloid\"}}"));
	}

	@Test
	public void testValidateWithBoolShouldArray() {
		// call under test
		assertDoesNotThrow(() -> validate("{\"bool\":{\"should\":["
				+ "{\"match\":{\"abstract\":\"x\"}},"
				+ "{\"multi_match\":{\"query\":\"x\",\"fields\":[\"title^3\"]}}"
				+ "],\"minimum_should_match\":1}}"));
	}

	@Test
	public void testValidateWithDisMax() {
		// call under test
		assertDoesNotThrow(() -> validate("{\"dis_max\":{\"tie_breaker\":0.3,\"queries\":["
				+ "{\"match\":{\"abstract\":\"x\"}},"
				+ "{\"match\":{\"summary\":\"x\"}}"
				+ "]}}"));
	}

	@Test
	public void testValidateWithConstantScoreAndBoosting() {
		assertDoesNotThrow(() -> validate(
				"{\"constant_score\":{\"filter\":{\"term\":{\"species\":\"human\"}},\"boost\":2.0}}"));
		// call under test
		assertDoesNotThrow(() -> validate("{\"boosting\":{"
				+ "\"positive\":{\"match\":{\"abstract\":\"x\"}},"
				+ "\"negative\":{\"match\":{\"abstract\":\"draft\"}},"
				+ "\"negative_boost\":0.2}}"));
	}

	@Test
	public void testValidateWithInlineTermsAllowed() {
		// call under test
		assertDoesNotThrow(() -> validate("{\"terms\":{\"assay\":[\"rnaSeq\",\"wgs\"]}}"));
	}

	@Test
	public void testValidateWithScriptRejected() {
		// script = Painless execution; not allowlisted. call under test
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> validate("{\"script\":{\"script\":\"doc['x'].value > 1\"}}"));
		assertTrue(e.getMessage().contains("not allowed"));
	}

	@Test
	public void testValidateWithScriptScoreRejected() {
		// call under test
		assertThrows(IllegalArgumentException.class, () -> validate(
				"{\"script_score\":{\"query\":{\"match_all\":{}},\"script\":{\"source\":\"1\"}}}"));
	}

	@Test
	public void testValidateWithWrapperRejected() {
		// wrapper embeds a base64 query that would bypass this walk entirely. call under test
		assertThrows(IllegalArgumentException.class,
				() -> validate("{\"wrapper\":{\"query\":\"eyJ0ZXJtIjp7fX0=\"}}"));
	}

	@Test
	public void testValidateWithUnknownClauseRejected() {
		// more_like_this can reach other indices; not allowlisted. call under test
		assertThrows(IllegalArgumentException.class,
				() -> validate("{\"more_like_this\":{\"like\":[{\"_index\":\"other\",\"_id\":\"1\"}]}}"));
	}

	@Test
	public void testValidateWithTermsLookupRejected() {
		// The terms lookup form references another index — rejected even though terms is allowed.
		// call under test
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> validate(
				"{\"terms\":{\"assay\":{\"index\":\"other\",\"id\":\"1\",\"path\":\"vals\"}}}"));
		assertTrue(e.getMessage().contains("lookup"));
	}

	@Test
	public void testValidateWithEmbeddedScriptKeyRejected() {
		// An allowed clause must not smuggle a forbidden 'script' key in its body. call under test
		assertThrows(IllegalArgumentException.class, () -> validate(
				"{\"bool\":{\"must\":[{\"match\":{\"abstract\":{\"query\":\"x\",\"script\":\"y\"}}}]}}"));
	}

	@Test
	public void testValidateWithMultipleClauseKeysRejected() {
		// A clause object must have exactly one clause type. call under test
		assertThrows(IllegalArgumentException.class,
				() -> validate("{\"match\":{\"a\":\"x\"},\"term\":{\"b\":\"y\"}}"));
	}

	@Test
	public void testValidateWithNullRejected() {
		// call under test
		assertThrows(IllegalArgumentException.class, () -> SearchQueryDslAllowlist.validate(null));
	}

	@Test
	public void testValidateWithDepthExceeded() {
		StringBuilder open = new StringBuilder();
		StringBuilder close = new StringBuilder();
		for (int i = 0; i < SearchQueryDslAllowlist.MAX_DEPTH + 1; i++) {
			open.append("{\"bool\":{\"must\":[");
			close.append("]}}");
		}
		String deep = open.toString() + "{\"match_all\":{}}" + close.toString();
		// call under test
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> validate(deep));
		assertTrue(e.getMessage().contains("deeply"));
	}

	@Test
	public void testValidateWithClauseCountExceeded() {
		StringBuilder sb = new StringBuilder("{\"bool\":{\"should\":[");
		for (int i = 0; i < SearchQueryDslAllowlist.MAX_CLAUSES + 1; i++) {
			if (i > 0) {
				sb.append(",");
			}
			sb.append("{\"match\":{\"abstract\":\"x\"}}");
		}
		sb.append("]}}");
		// call under test
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> validate(sb.toString()));
		assertTrue(e.getMessage().contains("too many clauses"));
	}
}
