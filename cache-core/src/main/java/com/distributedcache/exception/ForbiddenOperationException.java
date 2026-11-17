package com.distributedcache.exception;

/** Operation disabled by configuration, e.g. failure simulation outside demo mode (HTTP 403). */
public class ForbiddenOperationException extends CacheException {
    public ForbiddenOperationException(String message) { super(message); }
}
