package com.distributedcache.exception;

/** Unknown node / simulation / recommendation (maps to HTTP 404). */
public class NotFoundException extends CacheException {
    public NotFoundException(String message) { super(message); }
}
