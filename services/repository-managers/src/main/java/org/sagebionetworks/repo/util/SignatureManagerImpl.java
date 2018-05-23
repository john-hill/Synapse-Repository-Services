package org.sagebionetworks.repo.util;

import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.Date;

import org.sagebionetworks.SecretProvider;
import org.sagebionetworks.repo.model.SignedTokenInterface;
import org.sagebionetworks.repo.model.auth.NewUser;
import org.sagebionetworks.repo.model.principal.AccountCreationToken;
import org.sagebionetworks.repo.model.principal.EmailValidationSignedToken;
import org.sagebionetworks.schema.adapter.JSONObjectAdapterException;
import org.sagebionetworks.schema.adapter.org.json.EntityFactory;
import org.sagebionetworks.securitytools.HMACUtils;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.beans.factory.annotation.Autowired;

public class SignatureManagerImpl implements SignatureManager {
	
	private static final String ENCRYPTION_CHARSET = Charset.forName("utf-8").name();
	public static final long EMAIL_VALIDATION_TIME_LIMIT_MILLIS = 24*3600*1000L; // 24 hours as milliseconds
	
	@Autowired
	SecretProvider secretProvider;

	@Override
	public AccountCreationToken createAccountCreationToken(NewUser user, Date now) {
		AccountCreationToken accountCreationToken = new AccountCreationToken();
		accountCreationToken.setEncodedMembershipInvtnSignedToken(user.getEncodedMembershipInvtnSignedToken());
		EmailValidationSignedToken emailValidationSignedToken = new EmailValidationSignedToken();
		emailValidationSignedToken.setEmail(user.getEmail());
		emailValidationSignedToken.setCreatedOn(now);
		signToken(emailValidationSignedToken);
		accountCreationToken.setEmailValidationSignedToken(emailValidationSignedToken);
		return accountCreationToken;
	}

	@Override
	public EmailValidationSignedToken createEmailValidationSignedToken(Long userId, String email, Date now) {
		EmailValidationSignedToken emailValidationSignedToken = new EmailValidationSignedToken();
		emailValidationSignedToken.setUserId(userId + "");
		emailValidationSignedToken.setEmail(email);
		emailValidationSignedToken.setCreatedOn(now);
		signToken(emailValidationSignedToken);
		return emailValidationSignedToken;
	}

	@Override
	public String validateEmailValidationSignedToken(EmailValidationSignedToken token, Date now) {
		if (token.getUserId() != null)
			throw new IllegalArgumentException("EmailValidationSignedToken.token.getUserId() must be null");
		String email = token.getEmail();
		ValidateArgument.required(email, "EmailValidationSignedToken.email");
		Date createdOn = token.getCreatedOn();
		ValidateArgument.required(createdOn, "EmailValidationSignedToken.createdOn");
		if (now.getTime() - createdOn.getTime() > EMAIL_VALIDATION_TIME_LIMIT_MILLIS)
			throw new IllegalArgumentException("Email validation link is out of date.");
		validateToken(token);
		return email;
	}

	@Override
	public String validateAdditionalEmailSignedToken(EmailValidationSignedToken token, String userId, Date now) {
	    ValidateArgument.required(token.getUserId(), "EmailValidationSignedToken.userId");
		if (!token.getUserId().equals(userId))
			throw new IllegalArgumentException("Invalid token for userId " + userId);
		String email = token.getEmail();
		ValidateArgument.required(email, "EmailValidationSignedToken.email");
		Date createdOn = token.getCreatedOn();
		ValidateArgument.required(createdOn, "EmailValidationSignedToken.createdOn");
		if (now.getTime() - createdOn.getTime() > EMAIL_VALIDATION_TIME_LIMIT_MILLIS)
			throw new IllegalArgumentException("Email validation link is out of date.");
		validateToken(token);
		return email;
	}

	@Override
	public void signToken(SignedTokenInterface token) {
		token.setHmac(generateSignature(token));
	}

	@Override
	public <T extends SignedTokenInterface> T validateToken(T token) {
		String hmac = token.getHmac();
		token.setHmac(null);
		String regeneratedHmac = generateSignature(token);
		if (!regeneratedHmac.equals(hmac)) 
			throw new IllegalArgumentException("Invalid digital signature.");
		token.setHmac(hmac);
		return token;
	}

	
	private String generateSignature(SignedTokenInterface token) {
		if (token.getHmac()!=null) throw new IllegalArgumentException("HMAC is added only after generating signature.");
		try {
			String jsonString = EntityFactory.createJSONStringForEntity(token);
			byte[] secretKey = secretProvider.getEncryptionKey();
			byte[] signatureAsBytes = HMACUtils.generateHMACSHA1SignatureFromRawKey(jsonString, secretKey);
			return new String(signatureAsBytes, ENCRYPTION_CHARSET);
		} catch (JSONObjectAdapterException e) {
			throw new RuntimeException(e);
		} catch (UnsupportedEncodingException e) {
			throw new RuntimeException(e);
		}
	}

}
