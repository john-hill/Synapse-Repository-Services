package org.sagebionetworks.repo.manager.search;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Allowlist validator for a caller-supplied OpenSearch suggesters object (the contents of the
 * {@code suggest} key of a search request — a map of suggestion name to suggester definition,
 * optionally alongside a top-level {@code text}).
 *
 * <p>Same posture as {@link SearchQueryDslAllowlist} / {@link SearchAggregationDslAllowlist}:
 * only the enumerated suggester types are permitted. Notably the phrase suggester's
 * {@code collate} option can carry a Painless script, so a defensive key scan rejects an
 * embedded {@code script} anywhere in a suggester body.</p>
 *
 * <p>Throws {@link IllegalArgumentException} (HTTP 400) on any violation.</p>
 */
final class SearchSuggestDslAllowlist {

	private SearchSuggestDslAllowlist() {
	}

	/** Suggester types a caller may use. */
	static final Set<String> ALLOWED_SUGGESTERS = Set.of("term", "phrase", "completion");

	/** Per-suggester structural keys that are not themselves a suggester type. */
	private static final Set<String> STRUCTURAL_KEYS = Set.of("text", "prefix", "regex");

	private static final Set<String> FORBIDDEN_KEYS = Set.of("script");

	static final int MAX_SUGGESTERS = 50;

	/**
	 * Validate a suggesters object — the top-level map of suggestion name to definition (plus
	 * an optional top-level {@code text}).
	 *
	 * @throws IllegalArgumentException if a definition uses a non-allowlisted suggester type or
	 *         carries a forbidden key, or there are too many suggesters.
	 */
	static void validate(JsonNode suggest) {
		if (suggest == null || suggest.isNull()) {
			throw new IllegalArgumentException("suggest must not be null");
		}
		if (!suggest.isObject()) {
			throw new IllegalArgumentException(
					"suggest must be a JSON object mapping suggestion name to definition");
		}
		int count = 0;
		Iterator<Map.Entry<String, JsonNode>> entries = suggest.fields();
		while (entries.hasNext()) {
			Map.Entry<String, JsonNode> entry = entries.next();
			// A top-level "text" applies to every suggestion; it is not itself a suggester.
			if ("text".equals(entry.getKey())) {
				continue;
			}
			if (++count > MAX_SUGGESTERS) {
				throw new IllegalArgumentException("too many suggesters (max " + MAX_SUGGESTERS + ")");
			}
			validateSuggesterDef(entry.getValue());
		}
	}

	private static void validateSuggesterDef(JsonNode def) {
		if (def == null || !def.isObject()) {
			throw new IllegalArgumentException("each suggester definition must be a JSON object");
		}
		String suggesterType = null;
		Iterator<Map.Entry<String, JsonNode>> fields = def.fields();
		while (fields.hasNext()) {
			Map.Entry<String, JsonNode> field = fields.next();
			String key = field.getKey();
			if (STRUCTURAL_KEYS.contains(key)) {
				continue;
			}
			if (ALLOWED_SUGGESTERS.contains(key)) {
				if (suggesterType != null) {
					throw new IllegalArgumentException(
							"a suggester definition must declare exactly one suggester type; found '"
									+ suggesterType + "' and '" + key + "'");
				}
				suggesterType = key;
				scanForbiddenKeys(field.getValue());
			} else {
				throw new IllegalArgumentException("suggester type is not allowed: '" + key
						+ "'. Allowed types: " + ALLOWED_SUGGESTERS);
			}
		}
		if (suggesterType == null) {
			throw new IllegalArgumentException(
					"each suggester definition must declare exactly one suggester type");
		}
	}

	private static void scanForbiddenKeys(JsonNode node) {
		if (node == null) {
			return;
		}
		if (node.isObject()) {
			Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
			while (fields.hasNext()) {
				Map.Entry<String, JsonNode> field = fields.next();
				if (FORBIDDEN_KEYS.contains(field.getKey())) {
					throw new IllegalArgumentException(
							"forbidden key in suggester: '" + field.getKey() + "'");
				}
				scanForbiddenKeys(field.getValue());
			}
		} else if (node.isArray()) {
			for (JsonNode element : node) {
				scanForbiddenKeys(element);
			}
		}
	}
}
