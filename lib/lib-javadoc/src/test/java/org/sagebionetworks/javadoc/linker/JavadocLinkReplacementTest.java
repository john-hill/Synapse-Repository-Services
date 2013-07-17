package org.sagebionetworks.javadoc.linker;

import static org.junit.Assert.assertEquals;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class JavadocLinkReplacementTest {
	
	@Test
	public void testBasic(){
		Map<String, String> replacements = new HashMap<String, String>();
		replacements.put("to.replace.one", "foo");
		replacements.put("to.replace.two", "bar");
		String input = "Some text {@link to.replace.one}, more then another link {@link to.replace.two }";
		String expected = "<a href=\"foo\">one</a>,<a href=\"bar\">two</a>";
		String result = new JavadocLinkReplacement().replace(input, replacements);
		assertEquals(expected, result);
	}

}
