package org.sagebionetworks.docusign;

import java.util.function.Supplier;

/**
 * Thread-safe cache for a DocuSign access token. The token is lazily fetched on
 * first access and refreshed when it expires or is explicitly invalidated.
 */
class DocuSignAccessTokenProvider {

	private final Supplier<TokenResult> tokenSupplier;
	private final long expiryBufferMillis;

	private String cachedAccessToken;
	private long cachedAccessTokenExpiryMillis;

	DocuSignAccessTokenProvider(Supplier<TokenResult> tokenSupplier, long expiryBufferMillis) {
		this.tokenSupplier = tokenSupplier;
		this.expiryBufferMillis = expiryBufferMillis;
	}

	synchronized String getAccessToken() {
		long now = System.currentTimeMillis();
		if (cachedAccessToken != null && now + expiryBufferMillis < cachedAccessTokenExpiryMillis) {
			return cachedAccessToken;
		}
		TokenResult fresh = tokenSupplier.get();
		cachedAccessToken = fresh.getAccessToken();
		cachedAccessTokenExpiryMillis = System.currentTimeMillis() + (fresh.getExpiresInSeconds() * 1000L);
		return cachedAccessToken;
	}

	synchronized void invalidateAccessToken() {
		cachedAccessToken = null;
		cachedAccessTokenExpiryMillis = 0L;
	}

	static class TokenResult {
		private final String accessToken;
		private final long expiresInSeconds;

		TokenResult(String accessToken, long expiresInSeconds) {
			this.accessToken = accessToken;
			this.expiresInSeconds = expiresInSeconds;
		}

		String getAccessToken() {
			return accessToken;
		}

		long getExpiresInSeconds() {
			return expiresInSeconds;
		}
	}
}
