package com.distributedcache.exception;

/** Client error: bad key, bad TTL, oversized value, invalid setting (maps to HTTP 400). */
public class InvalidRequestException extends CacheException {
    public InvalidRequestException(String message) { super(message); }
}
