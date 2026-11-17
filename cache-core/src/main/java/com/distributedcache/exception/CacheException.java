package com.distributedcache.exception;

/** Base class for all domain errors raised by the cache engine. */
public class CacheException extends RuntimeException {
    public CacheException(String message) { super(message); }
    public CacheException(String message, Throwable cause) { super(message, cause); }
}
