package org.sagebionetworks.repo.web;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Simple log provider for RequestBodyLogger.
 * 
 * @author John
 *
 */
public class RequestBodyLoggerProvider implements LogProvider{
	
	static private Log log = LogFactory.getLog(RequestBodyLogger.class);

	@Override
	public Log getLog() {
		return log;
	}

}
