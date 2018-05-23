package org.sagebionetworks.repo.util;

import java.util.Date;

import org.sagebionetworks.repo.model.SignedTokenInterface;
import org.sagebionetworks.repo.model.auth.NewUser;
import org.sagebionetworks.repo.model.principal.AccountCreationToken;
import org.sagebionetworks.repo.model.principal.EmailValidationSignedToken;

/**
 * Abstraction for managing resources that need HMAC signature creation and validation.
 * 
 *
 */
public interface SignatureManager {

	/**
	 * Create a signed AccountCreationToken.
	 * @param user
	 * @param now
	 * @return
	 */
	AccountCreationToken createAccountCreationToken(NewUser user, Date now);
	
	/**
	 * Create a signed EmailValidationSignedToken
	 * @param userId
	 * @param email
	 * @param now
	 * @return
	 */
	EmailValidationSignedToken createEmailValidationSignedToken(Long userId, String email, Date now);
	
	/**
	 * Validate the given token.
	 * @param token
	 * @param now
	 * @return
	 */
	String validateEmailValidationSignedToken(EmailValidationSignedToken token, Date now);
	
	/**
	 * 
	 * @param token
	 * @param userId
	 * @param now
	 * @return
	 */
	String validateAdditionalEmailSignedToken(EmailValidationSignedToken token, String userId, Date now);
	
	/**
	 * Sign the given token
	 * @param token
	 * @return
	 */
	void signToken(SignedTokenInterface token);
	
	/**
	 * Validate the given token
	 * @param token
	 * @return
	 */
	public <T extends SignedTokenInterface> T validateToken(T token);
	
}
