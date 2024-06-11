package org.sagebionetworks.repo.manager.authentication;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import org.sagebionetworks.repo.manager.AuthenticationManager;
import org.sagebionetworks.repo.manager.UserCredentialValidator;
import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.manager.feature.FeatureManager;
import org.sagebionetworks.repo.manager.oauth.OIDCTokenHelper;
import org.sagebionetworks.repo.manager.password.InvalidPasswordException;
import org.sagebionetworks.repo.manager.password.PasswordValidator;
import org.sagebionetworks.repo.model.AuthorizationUtils;
import org.sagebionetworks.repo.model.UnauthenticatedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.auth.AuthenticatedOn;
import org.sagebionetworks.repo.model.auth.AuthenticationDAO;
import org.sagebionetworks.repo.model.auth.ChangePasswordInterface;
import org.sagebionetworks.repo.model.auth.ChangePasswordWithCurrentPassword;
import org.sagebionetworks.repo.model.auth.ChangePasswordWithToken;
import org.sagebionetworks.repo.model.auth.ChangePasswordWithTwoFactorAuthToken;
import org.sagebionetworks.repo.model.auth.HasTwoFactorAuthToken;
import org.sagebionetworks.repo.model.auth.LoginRequest;
import org.sagebionetworks.repo.model.auth.LoginResponse;
import org.sagebionetworks.repo.model.auth.PasswordResetSignedToken;
import org.sagebionetworks.repo.model.auth.TwoFactorAuthDisableRequest;
import org.sagebionetworks.repo.model.auth.TwoFactorAuthLoginRequest;
import org.sagebionetworks.repo.model.auth.TwoFactorAuthOtpType;
import org.sagebionetworks.repo.model.auth.TwoFactorAuthResetRequest;
import org.sagebionetworks.repo.model.auth.TwoFactorAuthTokenContext;
import org.sagebionetworks.repo.model.dbo.persistence.DBOCredential;
import org.sagebionetworks.repo.model.feature.Feature;
import org.sagebionetworks.repo.model.principal.AliasType;
import org.sagebionetworks.repo.model.principal.PrincipalAlias;
import org.sagebionetworks.repo.model.principal.PrincipalAliasDAO;
import org.sagebionetworks.repo.transactions.WriteTransaction;
import org.sagebionetworks.repo.web.NotFoundException;
import org.sagebionetworks.repo.web.TwoFactorAuthRequiredException;
import org.sagebionetworks.securitytools.PBKDF2Utils;
import org.sagebionetworks.util.Clock;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.beans.factory.annotation.Autowired;

public class AuthenticationManagerImpl implements AuthenticationManager {

	public static final long LOCK_TIMOUTE_SEC = 5*60;

	public static final int MAX_CONCURRENT_LOCKS = 10;

	public static final String ACCOUNT_LOCKED_MESSAGE = "This account has been locked. Reason: too many requests. Please try again in five minutes.";

	@Autowired
	private AuthenticationDAO authDAO;
	
	@Autowired
	private AuthenticationReceiptTokenGenerator authenticationReceiptTokenGenerator;

	@Autowired
	private PasswordValidator passwordValidator;

	@Autowired
	private UserCredentialValidator userCredentialValidator;

	@Autowired
	private PrincipalAliasDAO principalAliasDAO;

	@Autowired
	private PasswordResetTokenGenerator passwordResetTokenGenerator;
	
	@Autowired
	private OIDCTokenHelper oidcTokenHelper;
	
	@Autowired
	private Clock clock;
	
	@Autowired
	private UserManager userManager;
	
	@Autowired
	private TwoFactorAuthManager twoFaManager;

	@Autowired
	private FeatureManager featureManager;
	
	@Override
	@WriteTransaction
	public void setPassword(Long principalId, String password) {
		passwordValidator.validatePassword(password);

		String passHash = PBKDF2Utils.hashPassword(password, null);
		authDAO.changePassword(principalId, passHash);
	}

	@WriteTransaction
	public long changePassword(ChangePasswordInterface changePasswordInterface){
		ValidateArgument.required(changePasswordInterface, "changePasswordInterface");

		final long userId;
		if (changePasswordInterface instanceof ChangePasswordWithCurrentPassword) {
			userId = validateChangePassword((ChangePasswordWithCurrentPassword) changePasswordInterface);
		} else if (changePasswordInterface instanceof ChangePasswordWithToken) {
			userId = validateChangePassword((ChangePasswordWithToken) changePasswordInterface);
		} else if (changePasswordInterface instanceof ChangePasswordWithTwoFactorAuthToken) {
			userId = validateChangePassword((ChangePasswordWithTwoFactorAuthToken) changePasswordInterface);
		} else {
			throw new IllegalArgumentException("Unknown implementation of ChangePasswordInterface");
		}

		setPassword(userId, changePasswordInterface.getNewPassword());
		userCredentialValidator.forceResetLoginThrottle(userId);
		return userId;
	}

	/**
	 *
	 * @param changePasswordWithCurrentPassword
	 * @return id of user for which password change occurred
	 */
	long validateChangePassword(ChangePasswordWithCurrentPassword changePasswordWithCurrentPassword) {
		ValidateArgument.required(changePasswordWithCurrentPassword.getUsername(), "changePasswordWithCurrentPassword.username");
		ValidateArgument.required(changePasswordWithCurrentPassword.getCurrentPassword(), "changePasswordWithCurrentPassword.currentPassword");

		final long userId = findUserIdForAuthentication(changePasswordWithCurrentPassword.getUsername());
		//we can ignore the return value here because we are not generating a new authentication receipt on success
		validateAuthReceiptAndCheckPassword(userId, changePasswordWithCurrentPassword.getCurrentPassword(), changePasswordWithCurrentPassword.getAuthenticationReceipt());
		
		authDAO.getModifiedOn(userId).ifPresent( modifiedOn -> {
			if (ChronoUnit.HOURS.between(modifiedOn.toInstant(), Instant.now()) <= DBOCredential.MIN_PASSWORD_CHANGE_HOURS) {
				throw new IllegalArgumentException("Your password was changed in the past 24 hours, you may change your password via email reset.");
			}
		});

		// Since this is an unauthenticated request, we need to check for the second factor if 2fa is enabled.
		validateTwoFactorRequirementForPasswordChange(userId);
		
		return userId;
	}

	/**
	 *
	 * @param changePasswordWithToken
	 * @return id of user for which password change occurred
	 */
	long validateChangePassword(ChangePasswordWithToken changePasswordWithToken){
		ValidateArgument.required(changePasswordWithToken.getPasswordChangeToken(), "changePasswordWithToken.passwordChangeToken");

		if(!passwordResetTokenGenerator.isValidToken(changePasswordWithToken.getPasswordChangeToken())){
			throw new IllegalArgumentException("Password reset token is invalid");
		}
		
		Long principalId = Long.parseLong(changePasswordWithToken.getPasswordChangeToken().getUserId());

		validateTwoFactorRequirementForPasswordChange(principalId);

		return principalId;
	}
	
	long validateChangePassword(ChangePasswordWithTwoFactorAuthToken request) {
		
		validateTwoFactorAuthTokenRequest(request, TwoFactorAuthTokenContext.PASSWORD_CHANGE);
		
		return request.getUserId();
	}
	
	void validateTwoFactorRequirementForPasswordChange(Long principalId) {
		if (featureManager.isFeatureEnabled(Feature.CHANGE_PASSWORD_2FA_CHECK_BYPASS)) {
			return;
		}
		
		UserInfo user = userManager.getUserInfo(principalId);

		// See https://sagebionetworks.jira.com/browse/PLFM-8273, when the user updates the user password we need to make sure that
		// the 2nd factor is used to perform the operations if enabled.
		if (user.hasTwoFactorAuthEnabled()) {
			throw new TwoFactorAuthRequiredException(user.getId(), twoFaManager.generate2FaToken(user, TwoFactorAuthTokenContext.PASSWORD_CHANGE));
		}
	}

	@Override
	public String getSecretKey(Long principalId) throws NotFoundException {
		return authDAO.getSecretKey(principalId);
	}

	@Override
	@WriteTransaction
	public void changeSecretKey(Long principalId) {
		authDAO.changeSecretKey(principalId);
	}
	
	@Override
	public PasswordResetSignedToken createPasswordResetToken(long userId) throws NotFoundException {
		return passwordResetTokenGenerator.getToken(userId);
	}

	@Override
	public boolean hasUserAcceptedTermsOfUse(Long id) throws NotFoundException {
		return authDAO.hasUserAcceptedToU(id);
	}
	
	@Override
	@WriteTransaction
	public void setTermsOfUseAcceptance(Long principalId, Boolean acceptance) {
		if (acceptance == null) {
			throw new IllegalArgumentException("Cannot \"unsign\" the terms of use");
		}
		authDAO.setTermsOfUseAcceptance(principalId, acceptance);
	}

	@Override
	public LoginResponse login(LoginRequest request, String tokenIssuer){
		ValidateArgument.required(request, "loginRequest");
		ValidateArgument.required(request.getUsername(), "LoginRequest.username");
		ValidateArgument.required(request.getPassword(), "LoginRequest.password");

		final long userId = findUserIdForAuthentication(request.getUsername());
		
		final String password = request.getPassword();
		final String authenticationReceipt = request.getAuthenticationReceipt();

		validateAuthReceiptAndCheckPassword(userId, password, authenticationReceipt);
		
		authDAO.getExpiresOn(userId).ifPresent( expirationDate -> {
			if (Instant.now().isBefore(expirationDate.toInstant())) {
				throw new InvalidPasswordException("Your password has expired, please request a password reset for your account.");
			}
		});
		
		return loginWithNoPasswordCheck(userId, tokenIssuer);
	}

	@Override
	public LoginResponse loginWithNoPasswordCheck(long principalId, String issuer) {
		UserInfo user = userManager.getUserInfo(principalId);
		
		if (user.hasTwoFactorAuthEnabled()) {
			throw new TwoFactorAuthRequiredException(principalId, twoFaManager.generate2FaToken(user, TwoFactorAuthTokenContext.AUTHENTICATION));
		}
		
		return getLoginResponseAfterSuccessfulAuthentication(principalId, issuer);
	}
	
	@Override
	public LoginResponse loginWith2Fa(TwoFactorAuthLoginRequest request, String issuer) {
		validateTwoFactorAuthTokenRequest(request, TwoFactorAuthTokenContext.AUTHENTICATION);
				
		return getLoginResponseAfterSuccessfulAuthentication(request.getUserId(), issuer);
	}
	
	@Override
	public void send2FaResetNotification(TwoFactorAuthResetRequest request) {
		if (featureManager.isFeatureEnabled(Feature.CHANGE_PASSWORD_2FA_CHECK_BYPASS)) {
			throw new UnsupportedOperationException("Operation not available");
		}
		
		ValidateArgument.required(request, "The request");
		ValidateArgument.required(request.getTwoFaResetEndpoint(), "The twoFaResetEndpoint");
		ValidateArgument.required(request.getUserId(), "The userId");
		ValidateArgument.required(request.getTwoFaToken(), "The twoFaToken");
		
		UserInfo user = userManager.getUserInfo(request.getUserId());
		
		// Can only be used while the user is authenticating
		if (!twoFaManager.validate2FaToken(user, TwoFactorAuthTokenContext.AUTHENTICATION, request.getTwoFaToken())) {
			throw new UnauthenticatedException("The provided 2fa token is invalid.");
		}
		
		twoFaManager.send2FaResetNotification(user, request.getTwoFaResetEndpoint());
	}
	
	@Override
	@WriteTransaction
	public void disable2FaWithToken(TwoFactorAuthDisableRequest request) {
		if (featureManager.isFeatureEnabled(Feature.CHANGE_PASSWORD_2FA_CHECK_BYPASS)) {
			throw new UnsupportedOperationException("Operation not available");
		}
		
		ValidateArgument.required(request, "The request");
		ValidateArgument.required(request.getTwoFaToken(), "The twoFaToken");
		ValidateArgument.required(request.getTwoFaResetToken(), "The twoFaResetToken");
		
		UserInfo user = userManager.getUserInfo(request.getTwoFaResetToken().getUserId());
		
		// We first validate the first factor (authentication credentials)
		if (!twoFaManager.validate2FaToken(user, TwoFactorAuthTokenContext.AUTHENTICATION, request.getTwoFaToken())) {
			throw new UnauthenticatedException("The provided 2fa token is invalid.");
		}
		
		if (!twoFaManager.validate2FaResetToken(user, request.getTwoFaResetToken())) {
			throw new UnauthenticatedException("The provided 2fa reset token is invalid.");
		}
		
		twoFaManager.disable2Fa(user);
	}
	
	void validateTwoFactorAuthTokenRequest(HasTwoFactorAuthToken request, TwoFactorAuthTokenContext context) {
		ValidateArgument.required(request, "The request");
		ValidateArgument.required(request.getUserId(), "The userId");
		ValidateArgument.required(request.getTwoFaToken(), "The twoFaToken");
		ValidateArgument.required(request.getOtpCode(), "The otpCode");
		ValidateArgument.required(context, "The context");
		
		UserInfo user = userManager.getUserInfo(request.getUserId());

		TwoFactorAuthOtpType otpType = request.getOtpType() == null ? TwoFactorAuthOtpType.TOTP : request.getOtpType();
		
		if (!twoFaManager.validate2FaToken(user, context, request.getTwoFaToken())) {
			throw new UnauthenticatedException("The provided 2fa token is invalid.");
		}
		
		boolean validCode = false;
		
		switch (otpType) {
		case TOTP:
			validCode = twoFaManager.validate2FaTotpCode(user, request.getOtpCode());
			break;
		case RECOVERY_CODE:
			validCode = twoFaManager.validate2FaRecoveryCode(user, request.getOtpCode());
			break;
		default:
			throw new UnsupportedOperationException("Code type " + otpType + " not supported yet.");
		}
				
		if (!validCode) {
			throw new UnauthenticatedException("The provided code is invalid.");
		}
	}
	
	public AuthenticatedOn getAuthenticatedOn(UserInfo userInfo) {
		if (AuthorizationUtils.isUserAnonymous(userInfo)) {
			throw new UnauthenticatedException("Cannot retrieve authentication time stamp for anonymous user.");
		}
		// Note the date will be null if the user has not logged in
		Date authenticatedOn = authDAO.getAuthenticatedOn(userInfo.getId());
		AuthenticatedOn result = new AuthenticatedOn();
		result.setAuthenticatedOn(authenticatedOn);
		return result;
	}

	/**
	 * Validate authenticationReceipt and then checks that the password is correct for the given principalId
	 * @param userId id of the user
	 * @param password password of the user
	 * @param authenticationReceipt Can be null. When valid, does not throttle attempts on consecutive incorrect passwords.
	 * @return authenticationReceipt if it is valid and password check passed. null, if the authenticationReceipt was invalid, but password check passed.
	 * @throws UnauthenticatedException if password check failed
	 */
	void validateAuthReceiptAndCheckPassword(final long userId, final String password, final String authenticationReceipt) {
		
		boolean isAuthenticationReceiptValid = authenticationReceiptTokenGenerator.isReceiptValid(userId, authenticationReceipt);
		//callers that have previously logged in successfully are able to bypass lockout caused by failed attempts
		boolean correctCredentials = isAuthenticationReceiptValid ? userCredentialValidator.checkPassword(userId, password) : userCredentialValidator.checkPasswordWithThrottling(userId, password);
		if(!correctCredentials){
			throw new UnauthenticatedException(UnauthenticatedException.MESSAGE_USERNAME_PASSWORD_COMBINATION_IS_INCORRECT);
		}
		// Now that the password has been verified,
		// ensure that if the current password is a weak password, only allow the user to reset via emailed token
		try{
			passwordValidator.validatePassword(password);
		} catch (InvalidPasswordException e){
			throw new PasswordResetViaEmailRequiredException("You must change your password via email reset.");
		}
	}

	LoginResponse getLoginResponseAfterSuccessfulAuthentication(long principalId, String issuer) {		
		String newAuthenticationReceipt = authenticationReceiptTokenGenerator.createNewAuthenticationReciept(principalId);
		String accessToken = oidcTokenHelper.createClientTotalAccessToken(principalId, issuer);
		boolean acceptsTermsOfUse = authDAO.hasUserAcceptedToU(principalId);
		authDAO.setAuthenticatedOn(principalId, clock.now());
		return createLoginResponse(accessToken, acceptsTermsOfUse, newAuthenticationReceipt);
	}
	
	private static LoginResponse createLoginResponse(String accessToken, boolean acceptsTermsOfUse, String newReceipt) {
		LoginResponse response = new LoginResponse();
		response.setAccessToken(accessToken);
		response.setAcceptsTermsOfUse(acceptsTermsOfUse);
		response.setAuthenticationReceipt(newReceipt);
		return response;
	}

	long findUserIdForAuthentication(final String usernameOrEmail){
		PrincipalAlias principalAlias = principalAliasDAO.findPrincipalWithAlias(usernameOrEmail, AliasType.USER_EMAIL, AliasType.USER_NAME);
		if (principalAlias == null){
			throw new UnauthenticatedException(UnauthenticatedException.MESSAGE_USERNAME_PASSWORD_COMBINATION_IS_INCORRECT);
		}
		return principalAlias.getPrincipalId();
	}

}
