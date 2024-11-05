package org.sagebionetworks.repo.web;

public class ProjectStorageLimitExceededException extends RuntimeException {
	
	public ProjectStorageLimitExceededException(String message) {
		super(message);
	}

}
