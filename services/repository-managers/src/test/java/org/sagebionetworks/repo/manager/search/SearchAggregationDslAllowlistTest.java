package org.sagebionetworks.repo.manager.search;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class SearchAggregationDslAllowlistTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static void validate(String dsl) {
		try {
			SearchAggregationDslAllowlist.validate(MAPPER.readTree(dsl));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@Test
	public void testValidateWithAllowedMetricAndBucket() {
		// call under test
		assertDoesNotThrow(() -> validate("{"
				+ "\"per_year\":{\"date_histogram\":{\"field\":\"published_on\",\"calendar_interval\":\"year\"}},"
				+ "\"citation_stats\":{\"stats\":{\"field\":\"citation_count\"}}"
				+ "}"));
	}

	@Test
	public void testValidateWithNestedSubAggregations() {
		// call under test
		assertDoesNotThrow(() -> validate("{"
				+ "\"by_assay\":{\"terms\":{\"field\":\"assay\"},"
				+ "\"aggs\":{\"distinct_donors\":{\"cardinality\":{\"field\":\"donor_id\"}}}}"
				+ "}"));
	}

	@Test
	public void testValidateWithScriptedMetricRejected() {
		// scripted_metric is not allowlisted. call under test
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> validate(
				"{\"x\":{\"scripted_metric\":{\"map_script\":\"state.x=1\"}}}"));
		assertTrue(e.getMessage().contains("not allowed"));
	}

	@Test
	public void testValidateWithEmbeddedScriptRejected() {
		// An allowed agg type must not carry a script. call under test
		assertThrows(IllegalArgumentException.class, () -> validate(
				"{\"x\":{\"terms\":{\"field\":\"assay\",\"script\":\"doc['x']\"}}}"));
	}

	@Test
	public void testValidateWithPipelineAggregationRejected() {
		// bucket_script (pipeline) is not allowlisted. call under test
		assertThrows(IllegalArgumentException.class, () -> validate(
				"{\"x\":{\"bucket_script\":{\"buckets_path\":{},\"script\":\"params.a\"}}}"));
	}

	@Test
	public void testValidateWithTwoTypesInOneDefRejected() {
		// An aggregation definition must declare exactly one type. call under test
		assertThrows(IllegalArgumentException.class, () -> validate(
				"{\"x\":{\"terms\":{\"field\":\"a\"},\"avg\":{\"field\":\"b\"}}}"));
	}

	@Test
	public void testValidateWithNoTypeRejected() {
		// call under test
		assertThrows(IllegalArgumentException.class, () -> validate("{\"x\":{\"meta\":{}}}"));
	}

	@Test
	public void testValidateWithNonObjectRejected() {
		// call under test
		assertThrows(IllegalArgumentException.class, () -> validate("[{\"terms\":{}}]"));
	}

	@Test
	public void testValidateWithDepthExceeded() {
		StringBuilder open = new StringBuilder();
		StringBuilder close = new StringBuilder();
		for (int i = 0; i < SearchAggregationDslAllowlist.MAX_DEPTH + 1; i++) {
			open.append("{\"a\":{\"terms\":{\"field\":\"f\"},\"aggs\":");
			close.append("}}");
		}
		String deep = open.toString() + "{}" + close.toString();
		// call under test
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> validate(deep));
		assertTrue(e.getMessage().contains("deeply"));
	}
}
