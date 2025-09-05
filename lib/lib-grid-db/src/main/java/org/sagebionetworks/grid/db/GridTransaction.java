package org.sagebionetworks.grid.db;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.core.annotation.AliasFor;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transaction configured to use the gridTransactionManager.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Transactional(
	transactionManager = "gridTransactionManager",
	isolation = Isolation.READ_COMMITTED, 
	propagation = Propagation.REQUIRED,
	rollbackFor = Throwable.class
)
public @interface GridTransaction {
	
	/**
	 * Alias for {@link Transactional#readOnly}. Defaults to {@code false}.
	 */
	@AliasFor(annotation = Transactional.class)
	boolean readOnly() default false;
}
