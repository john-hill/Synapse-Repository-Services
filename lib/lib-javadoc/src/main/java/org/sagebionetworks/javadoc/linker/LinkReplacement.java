package org.sagebionetworks.javadoc.linker;

import java.util.Map;

/**
 * Abstraction for replacing names with links to files.
 * 
 * @author John
 *
 */
public interface LinkReplacement {

	/**
	 * 
	 * @param input The string containing names that need to be replaced with links to files.
	 * 
	 * @param nameToPathMap Maps fully qualified names to file paths.
	 * @return The processed input string with all relevant values replaced.
	 */
	public String replace(String input, Map<String, String> nameToPathMap);
}
