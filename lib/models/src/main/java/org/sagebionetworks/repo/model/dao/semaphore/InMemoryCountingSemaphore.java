package org.sagebionetworks.repo.model.dao.semaphore;


/**
 * An implementation of a counting semaphore where are state is maintained in
 * memory. This class in thread-safe and designed to be used as a singleton
 * within an an application container.
 *
 */
public class InMemoryCountingSemaphore {
	
	/**
	 * Attempt to acquire a lock with the given key. This method is non-blocking
	 * and a lock is either available and issued immediately or not at all.
	 * 
	 * @param key
	 *            A unique key to lock on
	 * @param maxLockCount
	 *            The maximum number of locks of that can be issued to the given
	 *            key.
	 * @param timeoutSec
	 *            The maximum life of the lock in seconds. If the lock is not
	 *            released before this amount of time elapses then it will be
	 *            forfeit. The lock timeout can be refreshed with
	 *            {@link #refreshLockTimeout(String, String, long)}
	 * 
	 * @return The token for the lock. This token must be used to release the
	 *         lock. Returns null when no locks are available. The caller is
	 *         expected to releases the lock
	 *         {@link #releaseLock(String, String)} when finished with it.
	 */
	public synchronized String attemptToAcquireLock(String key, long timeoutSec,
			int maxLockCount){
		return null;
	}

	/**
	 * Release a lock using the token that was issued when the lock was
	 * acquired.
	 * 
	 * @param key
	 *            The same key that was used to acquire the lock.
	 * @param token
	 *            The lock token issued from
	 *            {@link #attemptToAcquireLock(String, long, int)}
	 * @return True if the lock release was successful. False if the was not
	 *         released because it already timed out and was forcefully
	 *         released.
	 *         
	 * @throws LockReleaseFailedException When the given token has already expired.        
	 */
	public synchronized void releaseLock(String key, String token){
		
	}
}
