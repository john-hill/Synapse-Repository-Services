package org.sagebionetworks.repo.manager.search;

import java.io.StringReader;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.json.JSONArray;
import org.json.JSONObject;
import org.opensearch.client.json.JsonpMapper;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.indices.IndexSettingsAnalysis;
import org.sagebionetworks.schema.adapter.JSONObjectAdapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.json.stream.JsonParser;

/**
 * Boundary helpers for the opaque-{@code "type": "object"} JSON values carried on the
 * search-feature DTOs &mdash; {@code TextAnalyzer.settings},
 * {@code SynonymSet.definition}, {@code SearchConfiguration.defaultAnalyzer}, and the
 * elements of {@code SearchConfiguration.columnAnalyzerOverrides} /
 * {@code ColumnAnalyzerOverrideEntry.analyzer}.
 *
 * <p>Four concerns:</p>
 * <ol>
 *   <li><b>Shape conversion.</b> {@link #parse(Object)} / {@link #toJsonString(Object)}
 *       / {@link #fromJsonString(String)} bridge the four shapes a curator-supplied
 *       value can take (raw JSON {@link String}, {@link JSONObject} / {@link JSONArray},
 *       {@link JSONObjectAdapter}, Jackson-friendly {@link Map} / {@link java.util.Collection}
 *       / scalar) to the canonical forms the pipeline needs.</li>
 *   <li><b>Reference detection.</b> {@link #readRef(Object)} /
 *       {@link #readRef(JsonNode)} surface the qualified-name string from a
 *       {@code {"$ref": "{org}-{name}"}} reference object, regardless of whether the
 *       caller passes a {@link Map}, {@link JSONObject}, or {@link JsonNode}.</li>
 *   <li><b>Inline materialization.</b> {@link #toInline(Object, Class)} converts an
 *       inline literal value (a {@link Map} / {@link JSONObject} / etc.) into the typed
 *       POJO of the inlined resource (e.g. {@code ColumnAnalyzerOverride}) so the rest of
 *       the pipeline can work with typed accessors.
 *       {@link #toInlineAnalyzerSettings(Object, Function)} is the analyzer-slot variant
 *       &mdash; the inline value is a bare OpenSearch {@code settings.analysis} block
 *       (not wrapped in a {@code TextAnalyzer} envelope), so it goes straight to the
 *       typed {@link IndexSettingsAnalysis}.</li>
 *   <li><b>Analyzer-typed splice + deserialize.</b>
 *       {@link #spliceRefsInFilterMap(JsonNode, String, Function)} replaces every
 *       {@code $ref} entry in an analyzer's filter map with its resolved JSON;
 *       {@link #resolveAnalyzerSettings(JsonNode, Function)} bundles that splice with the
 *       OpenSearch typed deserializer to hand callers a typed
 *       {@link IndexSettingsAnalysis}.</li>
 * </ol>
 *
 * <p>Synapse only verifies that the JSON parses and that any refs resolve. AOSS / the
 * typed analyzer deserializer validate the analyzer / token-filter shape itself.</p>
 */
public final class SearchOpaqueJsonUtil {

	/**
	 * The single-key reference shape: {@code {"$ref": "{organizationName}-{name}"}}.
	 * Same shape on every binding slot: SearchConfiguration.defaultAnalyzer,
	 * SearchConfiguration.columnAnalyzerOverrides[*], ColumnAnalyzerOverrideEntry.analyzer,
	 * and the entries inside a TextAnalyzer's settings.filter registry.
	 */
	public static final String REF_KEY = "$ref";

	/**
	 * Top-level key holding an analyzer's filter registry inside a TextAnalyzer's
	 * {@code settings} blob. Per the schema, {@code $ref} is only permitted as the value
	 * of an entry inside this map.
	 */
	public static final String FILTER_KEY = "filter";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	// JsonpMapper for the OpenSearch typed-deserializer in resolveAnalyzerSettings; owns
	// its own instance so callers don't have to plumb the OpenSearchClient transport in
	// just to get the typed analyzer model. The no-arg JacksonJsonpMapper picks up the
	// same Jackson defaults the client uses.
	private static final JsonpMapper JSONP_MAPPER = new JacksonJsonpMapper();

	private SearchOpaqueJsonUtil() {
		// utility
	}

	// ---------- shape conversion ----------

	/**
	 * Parse a JSON value into a Jackson tree. Accepts every shape an opaque-Object POJO
	 * field can hold: raw JSON {@link String}, {@link JSONObject} / {@link JSONArray},
	 * {@link JSONObjectAdapter}, Jackson-friendly {@link Map} / {@link java.util.Collection}
	 * / scalar.
	 *
	 * @throws IllegalArgumentException when {@code json} is {@code null} or fails to parse.
	 */
	public static JsonNode parse(Object json) {
		if (json == null) {
			throw new IllegalArgumentException("JSON object is required.");
		}
		try {
			return MAPPER.readTree(asJsonString(json));
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException("Invalid JSON: " + e.getOriginalMessage(), e);
		}
	}

	/**
	 * Render an opaque-JSON value to its canonical JSON-string form for persistence.
	 * Returns {@code null} when {@code json} is {@code null}.
	 */
	public static String toJsonString(Object json) {
		if (json == null) {
			return null;
		}
		return asJsonString(json);
	}

	/**
	 * Render any of the supported opaque-JSON value shapes to a JSON string. See the
	 * class javadoc for the accepted shapes. Package-private so each branch is
	 * independently testable.
	 */
	static String asJsonString(Object json) {
		if (json instanceof String) {
			return (String) json;
		}
		if (json instanceof JSONObject || json instanceof JSONArray) {
			return json.toString();
		}
		if (json instanceof JSONObjectAdapter) {
			return ((JSONObjectAdapter) json).toJSONString();
		}
		try {
			return MAPPER.writeValueAsString(json);
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException("Invalid JSON: " + e.getOriginalMessage(), e);
		}
	}

	/**
	 * Parse a JSON string read from a database column back into a generic Java tree
	 * (typically a {@link Map} for an object, a {@link java.util.List} for an array, or
	 * a scalar). {@code null} passes through.
	 */
	public static Object fromJsonString(String json) {
		if (json == null) {
			return null;
		}
		try {
			return MAPPER.readValue(json, Object.class);
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException("Invalid JSON: " + e.getOriginalMessage(), e);
		}
	}

	// ---------- reference detection ----------

	/**
	 * If {@code value} is the single-field reference shape {@code {"$ref": "..."}},
	 * return the qualified-name string; otherwise return {@code null}.
	 *
	 * <p>Accepts the post-DAO {@link JSONObject} shape, the {@link Map} shape (test
	 * fixtures / programmatic callers), and the wire-deserialized
	 * {@link JSONObjectAdapter} shape (controllers receive opaque-Object fields as a
	 * {@link JSONObjectAdapter} after JSON binding). Anything else &mdash; including a
	 * bare {@link String} scalar &mdash; returns {@code null}.</p>
	 */
	public static String readRef(Object value) {
		if (value instanceof JSONObject) {
			return readRefFromJsonObject((JSONObject) value);
		}
		if (value instanceof Map) {
			return readRefFromMap((Map<?, ?>) value);
		}
		if (value instanceof JSONObjectAdapter) {
			return readRefFromJsonObject(new JSONObject(((JSONObjectAdapter) value).toJSONString()));
		}
		return null;
	}

	static String readRefFromMap(Map<?, ?> map) {
		if (map.size() != 1) {
			return null;
		}
		Object ref = map.get(REF_KEY);
		return ref instanceof String ? (String) ref : null;
	}

	static String readRefFromJsonObject(JSONObject obj) {
		if (obj.length() != 1) {
			return null;
		}
		Object ref = obj.opt(REF_KEY);
		return ref instanceof String ? (String) ref : null;
	}

	/**
	 * If {@code node} is an object whose only field is {@link #REF_KEY} with a textual
	 * value, return that value; otherwise {@code null}. The Jackson-tree counterpart of
	 * {@link #readRef(Object)}.
	 */
	public static String readRef(JsonNode node) {
		if (node == null || !node.isObject() || node.size() != 1) {
			return null;
		}
		JsonNode ref = node.get(REF_KEY);
		return (ref != null && ref.isTextual()) ? ref.asText() : null;
	}

	/**
	 * Collect every qualified-name appearing as a {@link #REF_KEY} value anywhere in
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

	// ---------- inline materialization ----------

	/**
	 * Convert an inline-literal value (a value that's <i>not</i> a {@code $ref}) into
	 * the typed POJO of {@code clazz}. Callers should branch on
	 * {@link #readRef(Object)} first; {@code toInline} is for the inline branch.
	 *
	 * <p>Accepts {@link Map} / {@link java.util.Collection} / scalar trees as well as
	 * {@link JSONObject} / {@link JSONArray}; the latter are normalized to their
	 * JSON-string form before deserialization.</p>
	 *
	 * @throws IllegalArgumentException when the value cannot be deserialized as
	 *         {@code clazz}.
	 */
	public static <T> T toInline(Object value, Class<T> clazz) {
		if (value == null) {
			return null;
		}
		try {
			if (value instanceof JSONObject || value instanceof JSONArray) {
				return MAPPER.readValue(value.toString(), clazz);
			}
			return MAPPER.convertValue(value, clazz);
		} catch (IllegalArgumentException | JsonProcessingException e) {
			throw new IllegalArgumentException(
					"Invalid inline " + clazz.getSimpleName() + ": " + e.getMessage(), e);
		}
	}

	/**
	 * Parse an inline analyzer-slot literal &mdash; the bare OpenSearch
	 * {@code settings.analysis} block carried directly on
	 * {@code SearchConfiguration.defaultAnalyzer} or
	 * {@code ColumnAnalyzerOverrideEntry.analyzer} &mdash; into a typed
	 * {@link IndexSettingsAnalysis}, splicing any {@code $ref} entries inside the
	 * analyzer's filter map via {@code resolver}.
	 *
	 * <p>At create / update time callers pass {@code resolver = q -> null}: any {@code $ref}
	 * surfaces as {@link IllegalArgumentException}, since refs inside an inline-literal slot
	 * are not a supported feature. At index-build time callers pass the SynonymSet resolver
	 * so a TextAnalyzer that uses synonyms via {@code $ref} can still be inlined.</p>
	 *
	 * @return the typed analyzer settings; {@code null} when {@code value} is {@code null}.
	 * @throws IllegalArgumentException when the inline literal is malformed JSON, fails the
	 *         OpenSearch typed deserializer, or contains an unresolved {@code $ref}.
	 */
	public static IndexSettingsAnalysis toInlineAnalyzerSettings(Object value,
			Function<String, JsonNode> resolver) {
		if (value == null) {
			return null;
		}
		return resolveAnalyzerSettings(parse(value), resolver);
	}

	// ---------- $ref splicing ----------

	/**
	 * Splice every {@code {"$ref": "<qname>"}} entry inside the {@code root.filter} map
	 * with the JSON returned by {@code resolver.apply(qname)}. Mutates {@code root} in
	 * place.
	 *
	 * <p>Per the schema contract, {@code $ref} is only permitted as a direct value of an
	 * entry in the top-level {@code filter} map of an analyzer's settings &mdash; that's
	 * why the splice is a single non-recursive pass over that map.</p>
	 *
	 * @param root          The settings tree; mutated in place.
	 * @param filterKey     The key of the top-level filter map (e.g. {@code "filter"}).
	 * @param resolver      Returns the JSON node to splice in for a given qname, or
	 *                      {@code null} if the target does not exist.
	 * @throws IllegalArgumentException when a {@code $ref} target does not resolve.
	 */
	public static void spliceRefsInFilterMap(JsonNode root, String filterKey,
			java.util.function.Function<String, JsonNode> resolver) {
		if (root == null) {
			return;
		}
		JsonNode filterMap = root.get(filterKey);
		if (filterMap == null || !filterMap.isObject()) {
			return;
		}
		ObjectNode filterObj = (ObjectNode) filterMap;
		for (Map.Entry<String, JsonNode> entry : iterable(filterObj.fields())) {
			String ref = readRef(entry.getValue());
			if (ref == null) {
				continue;
			}
			JsonNode target = resolver.apply(ref);
			if (target == null) {
				throw new IllegalArgumentException(
						"Unresolved $ref: '" + ref + "' at /" + filterKey + "/" + entry.getKey());
			}
			filterObj.set(entry.getKey(), target);
		}
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

	// ---------- analyzer-typed splice + deserialize ----------

	/**
	 * Splice every {@code {"$ref": "<qname>"}} entry inside the {@code root.}{@value
	 * #FILTER_KEY} map with the JSON returned by {@code resolver.apply(qname)}, then
	 * deserialize the resulting tree into the OpenSearch typed
	 * {@link IndexSettingsAnalysis}. Mutates {@code root} in place during the splice.
	 *
	 * <p>Downstream callers use typed accessors ({@link IndexSettingsAnalysis#analyzer()},
	 * {@link IndexSettingsAnalysis#filter()}, etc.) instead of re-walking the JSON
	 * tree.</p>
	 *
	 * @param root     The settings tree; mutated in place.
	 * @param resolver Returns the JSON node to splice in for a given qname, or
	 *                 {@code null} if the target does not exist.
	 * @return         The typed analyzer settings; {@code null} when {@code root} is
	 *                 {@code null}.
	 * @throws IllegalArgumentException when a {@code $ref} target does not resolve, or
	 *         the spliced tree fails the typed deserializer (malformed component).
	 */
	public static IndexSettingsAnalysis resolveAnalyzerSettings(JsonNode root,
			Function<String, JsonNode> resolver) {
		if (root == null) {
			return null;
		}
		spliceRefsInFilterMap(root, FILTER_KEY, resolver);
		try (JsonParser parser = JSONP_MAPPER.jsonProvider()
				.createParser(new StringReader(root.toString()))) {
			return IndexSettingsAnalysis._DESERIALIZER.deserialize(parser, JSONP_MAPPER);
		} catch (RuntimeException e) {
			throw new IllegalArgumentException(
					"Invalid analyzer settings: " + e.getMessage(), e);
		}
	}
}
