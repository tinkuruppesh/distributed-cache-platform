package com.distributedcache.exception;

/** The requested action is not allowed in the current state (maps to HTTP 409). */
public class ConflictException extends CacheException {
    public ConflictException(String message) { super(message); }
}
