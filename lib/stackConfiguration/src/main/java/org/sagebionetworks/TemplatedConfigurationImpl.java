package org.sagebionetworks;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Properties;
import java.util.Set;

/**
 * @author deflaux
 *
 */
public class TemplatedConfigurationImpl implements TemplatedConfiguration {

	private String defaultPropertiesFilename;
	private String templatePropertiesFilename;

	private Properties defaultStackProperties = null;
	private Properties stackPropertyOverrides = null;
	private Properties requiredProperties = null;
	private String propertyFileUrl = null;

	/**
	 * Pass in the default location for the properties file and also the
	 * template to use
	 * 
	 * @param defaultPropertiesFilename
	 * @param templatePropertiesFilename
	 */
	public TemplatedConfigurationImpl(String defaultPropertiesFilename,
			String templatePropertiesFilename) {
		this.defaultPropertiesFilename = defaultPropertiesFilename;
		this.templatePropertiesFilename = templatePropertiesFilename;
	}

	@Override
	public void reloadConfiguration() {
		defaultStackProperties = new Properties();
		stackPropertyOverrides = new Properties();
		requiredProperties = new Properties();

		// Load the default properties from the classpath.
		loadPropertiesFromClasspath(defaultPropertiesFilename,
				defaultStackProperties);
		// Load the required properties
		loadPropertiesFromClasspath(templatePropertiesFilename,
				requiredProperties);
		// Try loading the settings file
		addSettingsPropertiesToSystem(stackPropertyOverrides);
		
		Properties systemProperties = System.getProperties();
		for (Object propertyName : systemProperties.keySet()) {
			String value = (String)systemProperties.get(propertyName);
			if (value!=null && value.length()>0) {
				stackPropertyOverrides.setProperty((String)propertyName,
						value);
			}
		}
		String stack = getStack();
		String stackInstance = getStackInstance();

		propertyFileUrl = getPropertyOverridesFileURL();
		if ((null != propertyFileUrl) && (0 < propertyFileUrl.length())) {
			// Validate the property file
			StackUtils.validateStackProperty(stack + stackInstance,
					StackConstants.STACK_PROPERTY_FILE_URL, propertyFileUrl);

			// If we have IAM id and key the load the properties using the
			// Amazon
			// client, else the URL should be public.
			if (propertyFileUrl
					.startsWith(StackConstants.S3_PROPERTY_FILENAME_PREFIX)) {
				try {
					S3PropertyFileLoader.loadPropertiesFromS3(propertyFileUrl,
							stackPropertyOverrides);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			} else {
				loadPropertiesFromURL(propertyFileUrl, stackPropertyOverrides);
			}
			// Validate the required properties
			StackUtils.validateRequiredProperties(requiredProperties,
					stackPropertyOverrides, stack, stackInstance);
		}
	}

	public String getProperty(String propertyName) {
		String propertyValue = null;
		if (stackPropertyOverrides.containsKey(propertyName)) {
			propertyValue = stackPropertyOverrides.getProperty(propertyName);
		} else {
			propertyValue = defaultStackProperties.getProperty(propertyName);
		}
		// NullPointerExceptions further downstream are not very helpful, throw
		// here
		// instead. In general folks calling methods here do not want null
		// values,
		// but if they do, they can try/catch.
		//
		// Also note that required properties should be checked for existence by
		// out template
		// so this should only happen for optional properties that code is
		// requesting
		if (null == propertyValue) {
			throw new NullPointerException(
					"no value found in StackConfiguration for property "
							+ propertyName + " propertyFileURL="
							+ propertyFileUrl);
		}
		return propertyValue;
	}

	@Override
	public Set<String> getAllPropertyNames() {
		Set<String> allPropertyNames = new HashSet<String>();
		allPropertyNames.addAll(defaultStackProperties.stringPropertyNames());
		allPropertyNames.addAll(stackPropertyOverrides.stringPropertyNames());
		return allPropertyNames;
	}

	@Override
	public String getDecryptedProperty(String propertyName) {
		String stackEncryptionKey = getEncryptionKey();
		if (stackEncryptionKey == null || stackEncryptionKey.length() == 0)
			throw new RuntimeException(
					"Expected system property org.sagebionetworks.stackEncryptionKey");
		String encryptedProperty = getProperty(propertyName);
		if (encryptedProperty == null || encryptedProperty.length() == 0)
			throw new RuntimeException("Expected property for " + propertyName);
		StringEncrypter se = new StringEncrypter(stackEncryptionKey);
		String clearTextPassword = se.decrypt(encryptedProperty);
		return clearTextPassword;
	}

	private void loadPropertiesFromClasspath(String filename,
			Properties properties) {
		if (filename == null)
			throw new IllegalArgumentException("filename cannot be null");
		if (properties == null)
			throw new IllegalArgumentException("properties cannot be null");
		URL propertiesLocation = TemplatedConfigurationImpl.class
				.getResource(filename);
		if (null == propertiesLocation) {
			throw new IllegalArgumentException(
					"Could not load property file from classpath: " + filename);
		}
		try {
			properties.load(propertiesLocation.openStream());
		} catch (Exception e) {
			throw new Error(e);
		}
	}

	/**
	 * Add the properties from the settings file to the system properties if
	 * they are there.
	 */
	private void addSettingsPropertiesToSystem(Properties properties) {
		Properties props;
		try {
			props = SettingsLoader.loadSettingsFile();
			if (props != null) {
				Iterator it = props.keySet().iterator();
				while (it.hasNext()) {
					String key = (String) it.next();
					String value = props.getProperty(key);
					System.setProperty(key, value);
					properties.setProperty(key, value);
				}
			}
		} catch (Exception e) {
			throw new Error(e);
		}
	}

	/**
	 * Load a property file from a URL
	 * 
	 * @param url
	 * @param properties
	 */
	private void loadPropertiesFromURL(String url, Properties properties) {
		if (url == null)
			throw new IllegalArgumentException("url cannot be null");
		if (properties == null)
			throw new IllegalArgumentException("properties cannot be null");
		URL propertiesLocation;
		try {
			propertiesLocation = new URL(url);
		} catch (MalformedURLException e1) {
			throw new IllegalArgumentException(
					"Could not load property file from url: " + url, e1);
		}
		try {
			properties.load(propertiesLocation.openStream());
		} catch (Exception e) {
			throw new Error(e);
		}
	}

	/**
	 * Throws the same RuntimeException when a required property is missing.
	 * 
	 * @param propertyKey
	 * @param alternate
	 */
	private void throwRequiredPropertyException(String propertyKey,
			String alternate) {
		throw new RuntimeException("The property: " + propertyKey
				+ " or its alternate: " + alternate
				+ " is required and cannot be null");
	}

	@Override
	public String getPropertyOverridesFileURL() {
		String url = System.getProperty(StackConstants.PARAM1);
		if (url == null)
			url = System.getProperty(StackConstants.STACK_PROPERTY_FILE_URL);
		return url;
	}

	@Override
	public String getStack() {
		String stack = System.getProperty(StackConstants.PARAM3);
		if (stack == null)
			stack = System.getProperty(StackConstants.STACK_PROPERTY_NAME);
		if (stack == null)
			throwRequiredPropertyException(StackConstants.STACK_PROPERTY_NAME,
					StackConstants.PARAM3);
		return stack;
	}

	@Override
	public String getStackInstance() {
		String instance = System.getProperty(StackConstants.PARAM4);
		if (instance == null)
			instance = System
					.getProperty(StackConstants.STACK_INSTANCE_PROPERTY_NAME);
		if (instance == null)
			throwRequiredPropertyException(
					StackConstants.STACK_INSTANCE_PROPERTY_NAME,
					StackConstants.PARAM4);
		return instance;
	}

	@Override
	public String getAuthenticationServicePrivateEndpoint() {
		return getProperty("org.sagebionetworks.authenticationservice.privateendpoint");
	}

	@Override
	public String getAuthenticationServicePublicEndpoint() {
		return getProperty("org.sagebionetworks.authenticationservice.publicendpoint");
	}

	@Override
	public String getRepositoryServiceEndpoint() {
		return getProperty("org.sagebionetworks.repositoryservice.endpoint");
	}
	
	public String getFileServiceEndpoint() {
		return getProperty("org.sagebionetworks.fileservice.endpoint");
	}
	
	@Override
	public String getSearchServiceEndpoint() {
		return getProperty("org.sagebionetworks.searchservice.endpoint");
	}

	@Override
	public String getDockerServiceEndpoint() {
		return getProperty("org.sagebionetworks.docker.endpoint");
	}
	
	@Override
	public String getDockerRegistryListenerEndpoint() {
		return getProperty("org.sagebionetworks.docker.registry.listener.endpoint");
	}

	@Override
	public int getHttpClientMaxConnsPerRoute() {
		// We get connection timeouts from HttpClient if max conns is zero,
		// which is a confusing
		// error, so instead check more vigorously for that configuration
		// mistake
		String maxConnsPropertyName = "org.sagebionetworks.httpclient.connectionpool.maxconnsperroute";
		int maxConns = Integer.parseInt(getProperty(maxConnsPropertyName));
		if (1 > maxConns) {
			throw new IllegalArgumentException(maxConnsPropertyName
					+ " must be greater than zero");
		}
		return maxConns;
	}

}
