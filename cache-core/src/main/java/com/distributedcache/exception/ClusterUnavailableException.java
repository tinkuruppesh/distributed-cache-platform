package com.distributedcache.exception;

/** No eligible node in the key's preference list could serve the request. */
public class ClusterUnavailableException extends CacheException {
    public ClusterUnavailableException(String message) { super(message); }
}
