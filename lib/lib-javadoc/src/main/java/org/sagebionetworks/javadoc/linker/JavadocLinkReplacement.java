package org.sagebionetworks.javadoc.linker;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This implementation will replace javadoc style links '{@link ...}' with an
 * HTML link '<a>' for REST resources.
 * For Example: 
 * {@link org.sagebionetworks.repo.model.file.FileHandle}
 * Would be replaced with:
 * <a href="../../org/sagebionetworks/repo/model/file/FileHandle">FileHandle</a>
 * @author John
 *
 */
public class JavadocLinkReplacement implements LinkReplacement {

	/**
	 * ${...}
	 */
	private static final String LINK_REG_EX = "(\\{\\@link)([^\\{\\}]*)(\\})";
	private static final Pattern lINK_PATTERN = Pattern.compile(LINK_REG_EX);
	private static int LINK_SIZE = "{@link ".length();
	
	@Override
	public String replace(String input, Map<String, String> nameToPathMap) {
		if(input == null) throw new IllegalArgumentException("Input string cannot be null");
		if(nameToPathMap == null) throw new IllegalArgumentException("Replacement map cannot be null"); 
		Matcher matcher = lINK_PATTERN.matcher(input);
        boolean result = matcher.find();
        if (result) {
        	// This will contain the new string
            StringBuffer sb = new StringBuffer();
            do {
            	// The group will be a raw value like: ${<key>}
            	String group = matcher.group();
            	// extract the key by removing the first two and last characters.
            	String key = group.substring(LINK_SIZE, group.length()-1).trim();
            	// Lookup the replacement value from the provided map
            	String value = nameToPathMap.get(key);
            	if(value == null) {
            		throw new IllegalArgumentException("No replacement found for key: "+key);
            	}
            	// Replace the entire group with the value.
            	matcher.appendReplacement(sb, value);
                result = matcher.find();
            } while (result);
            // Add add anything left
            matcher.appendTail(sb);
            return sb.toString();
        }
        // There were no matches.
        return input;
	}

}
