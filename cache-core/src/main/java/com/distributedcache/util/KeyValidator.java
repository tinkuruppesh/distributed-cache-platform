package com.distributedcache.util;

import com.distributedcache.exception.InvalidRequestException;
import java.util.regex.Pattern;

/** Input validation shared by the gateway and the node protocol (keys must never contain spaces/newlines). */
public final class KeyValidator {
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9:_\\-.]{1,200}");
    private static final long MAX_TTL_MS = 30L * 24 * 3600 * 1000;
    private KeyValidator() {}

    public static void validateKey(String key) {
        if (key == null || !KEY.matcher(key).matches())
            throw new InvalidRequestException("Invalid key. Use 1-200 chars from [A-Za-z0-9:_-.] (no spaces).");
    }
    public static void validateValue(String value, int maxChars) {
        if (value == null) throw new InvalidRequestException("Value is required.");
        if (value.length() > maxChars) throw new InvalidRequestException("Value too large (max " + maxChars + " chars).");
    }
    public static void validateTtlMs(long ttlMs) {
        if (ttlMs < 0 || ttlMs > MAX_TTL_MS) throw new InvalidRequestException("TTL must be between 0 and 30 days.");
    }
}
