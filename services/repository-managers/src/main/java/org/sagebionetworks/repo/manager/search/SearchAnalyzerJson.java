package org.sagebionetworks.repo.manager.search;

import java.io.StringReader;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.opensearch.client.json.JsonpMapper;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.indices.IndexSettingsAnalysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.json.stream.JsonParser;

/**
 * Boundary helpers for the opaque-JSON {@code settings} blob carried on a TextAnalyzer
 * (and the {@code definition} blob on a SynonymSet). The public API is small and shaped
 * around the only three things callers do with these blobs:
 *
 * <ol>
 *   <li>{@link #parse(String)} &mdash; parse the curator-supplied string into a tree so
 *       the rest of the pipeline (existence checks, $ref splice) can walk it.</li>
 *   <li>{@link #collectRefs(JsonNode)} &mdash; surface every {@code $ref} qualified name
 *       so the caller can verify each target SynonymSet exists before any work that
 *       would otherwise fail later.</li>
 *   <li>{@link #resolveRefs(JsonNode, Function)} &mdash; substitute each
 *       {@code {"$ref": "{org}-{name}"}} entry inside the top-level {@code filter} map
 *       with its referenced SynonymSet definition, then deserialize the spliced tree
 *       into the OpenSearch Java client's typed {@link IndexSettingsAnalysis}. Downstream
 *       callers then operate on typed accessors instead of walking JSON.</li>
 * </ol>
 *
 * <p>Per the TextAnalyzer schema, a {@code $ref} is only permitted as a direct value of
 * an entry in the top-level {@code filter} map (it replaces an inline filter
 * definition). SynonymSet definitions are themselves single token-filter blobs and
 * cannot contain {@code $ref}, so resolution is a single non-recursive substitution
 * pass.</p>
 *
 * <p>Synapse only verifies (a) that the JSON parses and (b) that any refs resolve. AOSS
 * validates the analyzer / token-filter shape itself at index-build time.</p>
 */
public final class SearchAnalyzerJson {

	static final String REF_KEY = "$ref";

	static final String FILTER_KEY = "filter";

	/**
	 * The required entry name inside a TextAnalyzer's inner {@code analyzer} map. Field
	 * mappings and {@code SearchConfiguration.defaultAnalyzer} resolve a bare qualified
	 * name to this entry; OpenSearch promotes it to the index's reserved
	 * {@code analysis.analyzer.default} slot when the TextAnalyzer is the configuration's
	 * primary.
	 */
	public static final String DEFAULT_ANALYZER_KEY = "default";

	/**
	 * The optional entry name inside a TextAnalyzer's inner {@code analyzer} map for
	 * asymmetric search analysis. When present alongside {@link #DEFAULT_ANALYZER_KEY},
	 * the configuration's primary TextAnalyzer also promotes it to the index's reserved
	 * {@code analysis.analyzer.default_search} slot.
	 */
	public static final String DEFAULT_SEARCH_ANALYZER_KEY = "default_search";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	// SearchAnalyzerJson owns its own JsonpMapper so callers don't have to plumb the
	// OpenSearchClient's transport in just to deserialize the analyzer tree. The no-arg
	// JacksonJsonpMapper constructor picks up the same Jackson defaults the client uses.
	private static final JsonpMapper JSONP_MAPPER = new JacksonJsonpMapper();

	private SearchAnalyzerJson() {
		// utility
	}

	/**
	 * Parse a JSON-object string. Throws {@link IllegalArgumentException} with a
	 * user-facing message on malformed JSON.
	 */
	public static JsonNode parse(String json) {
		if (json == null) {
			throw new IllegalArgumentException("JSON string is required.");
		}
		try {
			return MAPPER.readTree(json);
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException("Invalid JSON: " + e.getOriginalMessage(), e);
		}
	}

	/**
	 * Collect every qualified-name appearing as a {@code $ref} value anywhere in
	 * {@code root}. Returns a deduplicated, ordered set in walk order.
	 */
	public static Set<String> collectRefs(JsonNode root) {
		if (root == null) {
			return Collections.emptySet();
		}
		Set<String> refs = new LinkedHashSet<>();
		for (JsonNode v : root.findValues(REF_KEY)) {
			if (v.isTextual()) {
				refs.add(v.asText());
			}
		}
		return refs;
	}

	/**
	 * Splice every {@code {"$ref": "<qname>"}} entry in the top-level {@code filter} map
	 * with the JSON returned by {@code resolver.apply(qname)}, then deserialize the
	 * resulting tree into the OpenSearch Java client's typed
	 * {@link IndexSettingsAnalysis}.
	 *
	 * <p>The boundary deserialization is the whole point of this helper: every caller
	 * downstream of {@code resolveRefs} can use typed accessors
	 * ({@link IndexSettingsAnalysis#analyzer()}, {@link IndexSettingsAnalysis#filter()},
	 * etc.) instead of re-walking a {@link JsonNode}.</p>
	 *
	 * <p>{@code resolver} returns the SynonymSet's JSON definition for a qname, or
	 * {@code null} if the target does not exist. A null target is reported with the
	 * offending {@code /filter/<name>} pointer.</p>
	 *
	 * @param root     The JSON node holding the curator-supplied settings. Mutated in
	 *                 place to perform the splice.
	 * @param resolver Looks up a qname and returns its JSON definition, or {@code null}
	 *                 if the target does not exist.
	 * @return The typed analysis settings the OpenSearch Java client expects.
	 * @throws IllegalArgumentException when a {@code $ref} target qname does not resolve
	 *         or the spliced tree fails the typed deserializer (malformed component).
	 */
	public static IndexSettingsAnalysis resolveRefs(JsonNode root, Function<String, JsonNode> resolver) {
		if (root == null) {
			return null;
		}
		JsonNode filterMap = root.get(FILTER_KEY);
		if (filterMap != null && filterMap.isObject()) {
			ObjectNode filterObj = (ObjectNode) filterMap;
			for (Map.Entry<String, JsonNode> entry : iterable(filterObj.fields())) {
				String ref = readRef(entry.getValue());
				if (ref == null) {
					continue;
				}
				JsonNode target = resolver.apply(ref);
				if (target == null) {
					throw new IllegalArgumentException(
							"Unresolved $ref: '" + ref + "' at /" + FILTER_KEY + "/" + entry.getKey());
				}
				filterObj.set(entry.getKey(), target);
			}
		}
		return deserialize(root);
	}

	/**
	 * Snapshot an iterator into a one-shot iterable so callers can iterate it with
	 * an enhanced-for loop while still safely mutating the underlying collection.
	 */
	private static <T> Iterable<T> iterable(java.util.Iterator<T> it) {
		java.util.List<T> list = new java.util.ArrayList<>();
		it.forEachRemaining(list::add);
		return list;
	}

	private static IndexSettingsAnalysis deserialize(JsonNode tree) {
		try (JsonParser parser = JSONP_MAPPER.jsonProvider()
				.createParser(new StringReader(tree.toString()))) {
			return IndexSettingsAnalysis._DESERIALIZER.deserialize(parser, JSONP_MAPPER);
		} catch (RuntimeException e) {
			throw new IllegalArgumentException(
					"Invalid analyzer settings: " + e.getMessage(), e);
		}
	}

	/**
	 * If {@code node} is an object whose only field is {@code "$ref"} with a textual
	 * value, return that value; otherwise null.
	 */
	private static String readRef(JsonNode node) {
		if (node == null || !node.isObject() || node.size() != 1) {
			return null;
		}
		JsonNode ref = node.get(REF_KEY);
		return (ref != null && ref.isTextual()) ? ref.asText() : null;
	}
}
