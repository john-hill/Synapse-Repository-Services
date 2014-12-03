package org.sagebionetworks.repo.web;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.sagebionetworks.repo.model.auth.ChangePasswordRequest;
import org.sagebionetworks.repo.model.auth.LoginCredentials;
import org.sagebionetworks.repo.model.auth.NewIntegrationTestUser;
import org.sagebionetworks.repo.model.auth.SecretKey;
import org.sagebionetworks.repo.model.auth.Session;
import org.sagebionetworks.schema.adapter.JSONEntity;
import org.sagebionetworks.schema.adapter.JSONObjectAdapterException;
import org.sagebionetworks.schema.adapter.org.json.EntityFactory;


@Aspect
public class RequestBodyLogger {
	/**
	 * Classes that should not be logged, such as user's credentials.
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	private static Set<Class> toExclude = new HashSet<Class>(Arrays.asList(
			LoginCredentials.class,
			ChangePasswordRequest.class,
			SecretKey.class,
			Session.class,
			NewIntegrationTestUser.class
	));

	LogProvider logProvider;

	public void inspectRequestBody(JSONEntity request) {
		if (request != null) {
			if (!toExclude.contains(request.getClass())) {
				try {
					logProvider.getLog().info(
							EntityFactory.createJSONStringForEntity(request));
				} catch (JSONObjectAdapterException e) {
					logProvider.getLog().error("Failed to log request", e);
				}
			}
		}
	}
	/**
	 * Inspect all methods with the 'org.springframework.web.bind.annotation.RequestMapping' annotation.s
	 * @param pjp
	 * @return
	 * @throws Throwable
	 */
	@Around("@annotation(org.springframework.web.bind.annotation.RequestMapping)")
	public Object inspectRequestBody2(ProceedingJoinPoint pjp) throws Throwable {
		 Object[] args = pjp.getArgs();
		 if(args != null){
			 for(Object arg: args){
				 if(arg instanceof JSONEntity){
					 inspectRequestBody((JSONEntity)arg);
				 }
			 }
		 }
		return pjp.proceed();
	}

	/**
	 * Injected provider
	 * @param logProvider
	 */
	public void setLogProvider(LogProvider logProvider) {
		this.logProvider = logProvider;
	}
	
}
