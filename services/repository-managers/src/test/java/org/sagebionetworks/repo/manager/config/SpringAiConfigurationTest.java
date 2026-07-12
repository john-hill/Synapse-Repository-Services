package org.sagebionetworks.repo.manager.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.util.json.JsonParser;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Verifies that {@link SpringAiConfiguration#configureToolArgumentJsonParsing()} makes Spring AI's
 * shared tool-argument parser tolerant of unescaped control characters. This is the exact parse
 * (Spring AI's {@link JsonParser#getObjectMapper()}) that {@code MethodToolCallback} uses to turn an
 * LLM tool-call's arguments into a Java map before invoking the tool method. Bedrock/Claude emit
 * multi-line code (e.g. the runPython {@code script}) with raw newlines, which the strict parser
 * rejects with "Illegal unquoted character (CTRL-CHAR, code 10)".
 */
public class SpringAiConfigurationTest {

	// A tool-call arguments payload with a raw (unescaped) newline inside the string value,
	// exactly as the model emits multi-line Python.
	private static final String MULTILINE_TOOL_ARGS = "{\"script\": \"import os\nprint(os.getcwd())\"}";

	@Test
	public void testStrictParserRejectsUnescapedControlChars() {
		// A strict, default Jackson mapper (matching Spring AI's out-of-the-box behavior) must reject
		// the raw newline. This proves the payload actually exercises the failure mode.
		ObjectMapper strictMapper = new ObjectMapper();
		assertThrows(JsonProcessingException.class,
				() -> strictMapper.readValue(MULTILINE_TOOL_ARGS, new TypeReference<Map<String, Object>>() {}));
	}

	@Test
	public void testConfigureToolArgumentJsonParsingAllowsMultilineScript() throws Exception {
		// call under test
		new SpringAiConfiguration().configureToolArgumentJsonParsing();

		// After configuration, Spring AI's shared parser (the one MethodToolCallback uses) parses the
		// multi-line script without throwing, and preserves the newline in the value.
		Map<String, Object> parsed = JsonParser.getObjectMapper()
				.readValue(MULTILINE_TOOL_ARGS, new TypeReference<Map<String, Object>>() {});

		assertEquals("import os\nprint(os.getcwd())", parsed.get("script"));
	}
}
