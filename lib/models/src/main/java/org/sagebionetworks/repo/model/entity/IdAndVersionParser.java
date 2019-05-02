package org.sagebionetworks.repo.model.entity;

/**
 * Synapse ID and version string::= [syn]<id>[.<version>]
 *
 * This instance will parse the string in a single pass.
 */
public class IdAndVersionParser {

	// Null char is used to indicate parser termination.
	private static final int NULL_CHAR = 0x0;

	private int index;
	private char[] chars;
	private char currentChar;
	IdAndVersionBuilder builder;

	IdAndVersionParser(String toParse) {
		if (toParse == null) {
			throw new IllegalArgumentException("Id string cannot be null");
		}
		index = 0;
		chars = toParse.toCharArray();
		if (chars.length < 1) {
			throw new IllegalArgumentException("Id must contain at least one character.");
		}
		currentChar = chars[index];
	}

	/**
	 * Parser the string in a single pass.
	 * 
	 * @return
	 */
	IdAndVersion parse() {
		try {
			IdAndVersionBuilder builder = new IdAndVersionBuilder();
			// ignore starting white space
			consumeWhiteSpace();
			// skip 'syn' if present.
			consumeSyn();
			// first long is the ID
			builder.setId(consumeLong());
			// version is optional so might be at the end.
			if (!isEnd()) {
				// Not at the end so the next char must be dot
				consumeDot();
				// second long is the version
				builder.setVersion(consumeLong());
				// ignore trailing whitespace.
				consumeWhiteSpace();
			}
			// Must be at the end
			if (!isEnd()) {
				throw new ParseException(index);
			}
			return builder.build();
		} catch (ParseException e) {
			throw new IllegalArgumentException("Invalid Entity ID: " + new String(chars), e);
		}
	}

	/**
	 * Consume the current character and fetch the next.
	 */
	private void consumeCharacter() {
		index++;
		if (index < chars.length) {
			currentChar = chars[index];
		} else {
			// set to null
			currentChar = NULL_CHAR;
		}
	}

	/**
	 * Parser is at the end if the current character is the null character.
	 * 
	 * @return
	 */
	private boolean isEnd() {
		return currentChar == NULL_CHAR;
	}

	/**
	 * Consume the 'dot' character.
	 * 
	 * @throws ParseException Thrown if the current character is not dot.
	 */
	private void consumeDot() throws ParseException {
		if (currentChar == '.') {
			consumeCharacter();
		} else {
			throw new ParseException(index);
		}
	}

	/**
	 * Consume a single Long from the character array
	 * 
	 * @return The Long read from the array.
	 * @throws ParseException 
	 */
	private long consumeLong() throws ParseException {
		boolean atLeastOneDigit = false;
		// consume all digits
		long value = 0;
		while (currentChar >= '0' && currentChar <= '9') {
			value *= 10L;
			value += ((long)currentChar - 48L);
			consumeCharacter();
			atLeastOneDigit = true;
		}
		if(!atLeastOneDigit) {
			throw new ParseException(index);
		}
		return value;
	}

	/**
	 * Consume case insensitive 'syn' if present.
	 * 
	 * @throws ParseExcpetion
	 */
	private void consumeSyn() throws ParseException {
		if (currentChar == 's' || currentChar == 'S') {
			consumeCharacter();
			if (currentChar == 'y' || currentChar == 'Y') {
				consumeCharacter();
			} else {
				throw new ParseException(index);
			}
			if (currentChar == 'n' || currentChar == 'N') {
				consumeCharacter();
			} else {
				throw new ParseException(index);
			}
		}
	}

	/**
	 * Skip over all whitespace.
	 */
	private void consumeWhiteSpace() {
		while (Character.isWhitespace(currentChar)) {
			consumeCharacter();
		}
	}

	/**
	 * Exception that indicates where the error occurred.
	 *
	 */
	public static class ParseException extends Exception {

		private static final long serialVersionUID = 1L;

		int errorIndex;

		public ParseException(int index) {
			super("Unexpected character at index: " + index);
			this.errorIndex = index;
		}

		/**
		 * The index of the error encountered.
		 * 
		 * @return
		 */
		public int getErrorIndex() {
			return errorIndex;
		}

	}

	/**
	 * Parse the given String into an EntityId.
	 * 
	 * @param toParse
	 * @return
	 */
	public static IdAndVersion parseEntityId(String toParse) {
		IdAndVersionParser parser = new IdAndVersionParser(toParse);
		return parser.parse();
	}
}
