package com.distributedcache.cache;

import java.util.Objects;

/**
 * Immutable cache record. A delete is stored as a "tombstone" (deleted = true) so that a stale replica
 * can never resurrect a deleted key during synchronization.
 *
 * version is a hybrid number: max(previousVersion + 1, wallClockMillis). It is strictly increasing per key
 * and, unlike a plain counter, still grows correctly after an eviction or a node restart.
 */
public record CacheEntry(String key, String value, long createdAt, long expiresAt,
                         long version, long updatedAt, boolean deleted) {

    public boolean isExpired(long now) { return expiresAt > 0 && now >= expiresAt; }

    /** -1 = no expiry, otherwise remaining milliseconds. */
    public long remainingTtlMillis(long now) { return expiresAt <= 0 ? -1 : Math.max(0, expiresAt - now); }

    /** Total order used everywhere to decide "which copy wins": version, then updatedAt, then value hash. */
    public boolean isNewerThan(CacheEntry other) {
        if (other == null) return true;
        if (version != other.version) return version > other.version;
        if (updatedAt != other.updatedAt) return updatedAt > other.updatedAt;
        return Integer.compare(Objects.hashCode(value), Objects.hashCode(other.value)) > 0;
    }

    public long approxBytes() { return 64L + 2L * key.length() + (value == null ? 0 : 2L * value.length()); }
}
