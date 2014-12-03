package org.sagebionetworks.repo.web;

import org.apache.commons.logging.Log;

/**
 * Allows for inversion of control for logging.
 * 
 * @author John
 * 
 */
public interface LogProvider {

	/**
	 * Get a log.
	 * 
	 * @return
	 */
	public Log getLog();

}
