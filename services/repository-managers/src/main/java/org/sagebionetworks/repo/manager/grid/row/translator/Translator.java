package org.sagebionetworks.repo.manager.grid.row.translator;

import org.sagebionetworks.repo.model.grid.patch.ConType;
import org.sagebionetworks.repo.model.grid.patch.ConValue;

/**
 * Translation from a String to a {@link ConValue}.
 */
public interface Translator {

	default ConValue translateNullable(String string) {
		return translateNullable(string, true);
	}

	default ConValue translateNullable(String string, boolean isRequiredProperty) {
		if (string == null) {
			if (isRequiredProperty) {
				return new ConValue(ConType.NULL, null);
			}
			return new ConValue(ConType.UNDEFINED, null);
		}
		return translate(string);
	}

	/**
	 * Translate from a String to ConValue
	 * 
	 * @param string
	 * @return
	 */
	ConValue translate(String string);

	default ConValue translateLeniently(String string) {
		return translateLeniently(string, true);
	}

	/**
	 * Like {@link #translateNullable(String, boolean)}, but never throws: a value
	 * this translator's type cannot represent is carried through untranslated
	 * rather than failing the caller. This is for callers re-reading data against
	 * a schema that was not derived from (and so is not guaranteed to fit) the data
	 * being read, e.g. a schema inferred from one CSV revision applied to another.
	 * <p>
	 * A blank value is treated as no value (the same result
	 * {@link #translateNullable(String, boolean)} produces for {@code null}), since
	 * a blank cell carries no type information for any column type. A non-blank
	 * value this translator cannot parse is translated as {@link ConType#STRING}.
	 *
	 * @param string             the raw value; {@code null} is treated as no value.
	 * @param isRequiredProperty whether a no-value result should be
	 *                           {@link ConType#NULL} (required) or
	 *                           {@link ConType#UNDEFINED} (not required).
	 */
	default ConValue translateLeniently(String string, boolean isRequiredProperty) {
		try {
			return translateNullable(string, isRequiredProperty);
		} catch (RuntimeException e) {
			if (string == null || string.trim().isEmpty()) {
				return translateNullable(null, isRequiredProperty);
			}
			return new ConValue(ConType.STRING, string);
		}
	}

}
