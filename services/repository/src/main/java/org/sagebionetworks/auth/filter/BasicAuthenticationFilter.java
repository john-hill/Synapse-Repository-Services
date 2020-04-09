package org.sagebionetworks.auth.filter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.sagebionetworks.StackConfiguration;
import org.sagebionetworks.auth.HttpAuthUtil;
import org.sagebionetworks.auth.UserNameAndPassword;
import org.sagebionetworks.cloudwatch.Consumer;
import org.sagebionetworks.cloudwatch.MetricUtils;
import org.sagebionetworks.cloudwatch.ProfileData;

import com.amazonaws.services.cloudwatch.model.StandardUnit;

/**
 * Implementation of a filter that extracts base64 encoded credentials from the
 * Authorization header using the basic scheme.
 * 
 * @author Marco Marasca
 *
 */
public abstract class BasicAuthenticationFilter implements Filter {

	private static final String MISSING_CREDENTIALS_MSG = "Missing required credentials in the authorization header.";
	private static final String INVALID_CREDENTIALS_MSG = "Invalid credentials.";
	private static final String CLOUD_WATCH_NAMESPACE_PREFIX = "Authentication";
	private static final String CLOUD_WATCH_METRIC_NAME = "BadCredentials";
	private static final String CLOUD_WATCH_DIMENSION_FILTER = "filterClass";
	private static final String CLOUD_WATCH_DIMENSION_MESSAGE = "message";
	private static final String CLOUD_WATCH_UNIT_COUNT = StandardUnit.Count.toString();

	private Log logger = LogFactory.getLog(getClass());
	
	private StackConfiguration config;
	private Consumer consumer;
	
	public BasicAuthenticationFilter(StackConfiguration config, Consumer consumer) {
		this.config = config;
		this.consumer = consumer;
	}

	@Override
	public final void doFilter(ServletRequest request, ServletResponse response, FilterChain filterChain)
			throws IOException, ServletException {

		if (!(request instanceof HttpServletRequest) || !(response instanceof HttpServletResponse)) {
			throw new ServletException("Only HTTP requests are supported");
		}

		HttpServletRequest httpRequest = (HttpServletRequest) request;
		HttpServletResponse httpResponse = (HttpServletResponse) response;

		Optional<UserNameAndPassword> credentials;

		try {
			credentials = HttpAuthUtil.getBasicAuthenticationCredentials(httpRequest);
		} catch (IllegalArgumentException e) {
			rejectRequest(httpResponse, e);
			return;
		}

		if (credentialsRequired() && !credentials.isPresent()) {
			rejectRequest(httpResponse, MISSING_CREDENTIALS_MSG);
			return;
		}

		if (credentials.isPresent() && !validCredentials(credentials.get())) {
			rejectRequest(httpResponse, getInvalidCredentialsMessage());
			return;
		}

		doFilterInternal(httpRequest, httpResponse, filterChain, credentials);
	}

	@Override
	public void init(FilterConfig filterConfig) throws ServletException {

	}

	@Override
	public void destroy() {

	}
	
	/**
	 * Rejects the http request due to the given exception sending a 401 in the response with the exception message.
	 * If {@link #reportBadCredentialsMetric()} is true sends a bad credentials metric to cloud watch
	 * 
	 * @param response
	 * @param ex
	 * @throws IOException
	 */
	protected void rejectRequest(HttpServletResponse response, Exception ex) throws IOException {
				
		if (reportBadCredentialsMetric()) {
			
			logger.error(ex.getMessage(), ex);
			
			// We log in cloudwatch the stack trace of the exception
			String stackTraceString = MetricUtils.stackTracetoString(ex);
			sendBadCredentialMetric(consumer, getClass().getName(), config.getStackInstance(), stackTraceString);
		}
		
		HttpAuthUtil.reject(response, ex.getMessage());
	}

	/**
	 * Rejects a the http request and sends a 401 in the response with the given
	 * message as the reason, if {@link #reportBadCredentialsMetric()} is true sends
	 * a bad credentials metric to cloud watch
	 * 
	 * @param response
	 * @param message The message to be returned in the response
	 * @throws IOException
	 */
	protected void rejectRequest(HttpServletResponse response, String message) throws IOException {
		if (reportBadCredentialsMetric()) {
			sendBadCredentialMetric(consumer, getClass().getName(), config.getStackInstance(), message);
		}

		HttpAuthUtil.reject(response, message);
	}

	/**
	 * Proceeds with the request invoking the
	 * {@link FilterChain#doFilter(javax.servlet.ServletRequest, javax.servlet.ServletResponse)}
	 * method. Can be overridden to alter the behavior in the filter chain.
	 * 
	 * @param request
	 * @param response
	 * @param filterChain
	 * @param credentials
	 * @throws ServletException
	 * @throws IOException
	 */
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain,
			Optional<UserNameAndPassword> credentials) throws ServletException, IOException {
		filterChain.doFilter(request, response);
	}

	/**
	 * @param credentials The credentials extracted from the Authorization header,
	 *                    always present. If the {@link #credentialsRequired()} is
	 *                    false and the credentials are not present this check will
	 *                    be skipped
	 * @return True if the given credentials are valid, false otherwise
	 */
	protected abstract boolean validCredentials(UserNameAndPassword credentials);

	/**
	 * @return True if the credentials are required, false otherwise (e.g. anonymous
	 *         access)
	 */
	protected boolean credentialsRequired() {
		return true;
	}

	/**
	 * @return True if the invoking the filter with bad or missing credentials
	 *         should lead to a report in cloud watch (e.g. for services that are
	 *         managed by the platform)
	 */
	protected boolean reportBadCredentialsMetric() {
		return false;
	}

	/**
	 * @return The message returned if the credentials are invalid
	 */
	protected String getInvalidCredentialsMessage() {
		return INVALID_CREDENTIALS_MSG;
	}

	private static void sendBadCredentialMetric(Consumer consumer, String filterClass, String stackInstance, String message) {
		
		Date timestamp = new Date();
		
		List<ProfileData> data = new ArrayList<>();
		
		// Note: Setting dimensions defines a new metric since the metric itself is identified by the name and dimensions
		// (See https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/cloudwatch_concepts.html#Dimension)
		// 
		// We send two different metrics (with the same timestamp) one that includes the message so that we can quickly inspect it
		// and one without the message so that an alarm can be created (since we don't know the message in advance it would be impossible
		// to create an alarm).
		
		data.add(generateProfileData(timestamp, filterClass, stackInstance, Optional.empty()));
		
		if (!StringUtils.isBlank(message)) {
			data.add(generateProfileData(timestamp, filterClass, stackInstance, Optional.of(message)));
		}
		
		consumer.addProfileData(data);
	}
	
	private static ProfileData generateProfileData(Date timestamp, String filterClass, String stackInstance, Optional<String> message) {
		ProfileData logEvent = new ProfileData();

		logEvent.setNamespace(String.format("%s - %s", CLOUD_WATCH_NAMESPACE_PREFIX, stackInstance));
		logEvent.setName(CLOUD_WATCH_METRIC_NAME);
		logEvent.setValue(1.0);
		logEvent.setUnit(CLOUD_WATCH_UNIT_COUNT);
		logEvent.setTimestamp(timestamp);
		
		Map<String, String> dimensions = new HashMap<>();
		
		dimensions.put(CLOUD_WATCH_DIMENSION_FILTER, filterClass);
		
		message.ifPresent( msg -> {
			dimensions.put(CLOUD_WATCH_DIMENSION_MESSAGE, msg);
		});
		
		logEvent.setDimension(dimensions);
		
		return logEvent;
	}

}
