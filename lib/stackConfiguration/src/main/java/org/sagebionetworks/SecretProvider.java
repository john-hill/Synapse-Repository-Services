package org.sagebionetworks;

public interface SecretProvider {

	/**
	 * Get the encryption key used by the stack.
	 * @return
	 */
	byte[] getEncryptionKey();

}
