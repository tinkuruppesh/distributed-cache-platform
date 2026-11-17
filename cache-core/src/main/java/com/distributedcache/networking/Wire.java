package com.distributedcache.networking;

import com.distributedcache.cache.CacheEntry;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Text protocol helpers. One request = one line: "<secret> <COMMAND> <args...>".
 * Values travel Base64-encoded so they can contain anything (spaces, newlines, unicode).
 * "-" stands for an empty/absent value.
 */
public final class Wire {
    private Wire() {}

    public static String b64(String s) {
        return (s == null || s.isEmpty()) ? "-" : Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    public static String unb64(String s) {
        return "-".equals(s) ? "" : new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
    }

    /** key valB64 createdAt expiresAt version updatedAt deleted */
    public static String encode(CacheEntry e) {
        return e.key() + " " + b64(e.value()) + " " + e.createdAt() + " " + e.expiresAt() + " "
                + e.version() + " " + e.updatedAt() + " " + (e.deleted() ? 1 : 0);
    }

    public static CacheEntry decode(String[] p, int off) {
        boolean deleted = "1".equals(p[off + 6]);
        return new CacheEntry(p[off], deleted ? null : unb64(p[off + 1]), Long.parseLong(p[off + 2]),
                Long.parseLong(p[off + 3]), Long.parseLong(p[off + 4]), Long.parseLong(p[off + 5]), deleted);
    }

    /** "OK a=1 b=2" -> {a:1,b:2} */
    public static Map<String, String> parseKv(String line) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String tok : line.split(" ")) {
            int i = tok.indexOf('=');
            if (i > 0) m.put(tok.substring(0, i), tok.substring(i + 1));
        }
        return m;
    }
}
