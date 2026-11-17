package com.distributedcache.api.cache;

import com.distributedcache.cache.CacheEntry;
import com.distributedcache.audit.AuditService;
import com.distributedcache.cluster.ClusterRuntime;
import com.distributedcache.exception.NotFoundException;
import com.distributedcache.routing.CacheRouter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Comparator;
import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;

/** Client-facing cache API (the "gateway"). SET / GET / DELETE / EXISTS / EXPIRE / TTL. */
@RestController
@RequestMapping("/api/v1/cache")
@Validated
public class CacheController {
    private static final String MASK = "••••••";
    private static final Pattern SENSITIVE = Pattern.compile("(?i).*(token|secret|password|passwd|session|auth|credential|apikey).*");

    public record SetRequest(@NotBlank @Size(max = 200) String key, @NotNull String value,
                             @Min(0) @Max(2_592_000) Long ttlSeconds) {}
    public record UpdateRequest(@NotNull String value, @NotNull @Min(0) @Max(2_592_000) Long ttlSeconds) {}
    public record ExpireRequest(@NotNull @Min(0) @Max(2_592_000) Long ttlSeconds) {}
    public record EntryResponse(String key, String value, boolean masked, long version, long createdAt, long updatedAt,
                                long expiresAt, long ttlMillis, String primary, List<String> replicas, String servedBy) {}
    public record ReadResponse(String key, boolean hit, String value, boolean masked, long version, long createdAt,
                               long updatedAt, long expiresAt, long ttlMillis, String servedBy, int attempts,
                               double latencyMs, CacheRouter.Placement placement) {}
    public record SetResponse(String key, long version, long expiresAt, String writtenTo, List<String> replicas,
                              int fallbackAttempts, String message) {}
    public record KeyInfo(String key, String valuePreview, boolean masked, long version, long createdAt, long updatedAt,
                          long expiresAt, long ttlMillis, long sizeBytes, String dataType,
                          String primary, List<String> replicas, double ringPosition) {}
    public record BulkDeleteRequest(@jakarta.validation.constraints.NotEmpty @jakarta.validation.constraints.Size(max = 500)
                                    List<@NotBlank @Size(max = 200) String> keys) {}

    private final ClusterRuntime rt;
    private final AuditService audit;

    public CacheController(ClusterRuntime rt, AuditService audit) { this.rt = rt; this.audit = audit; }

    private static boolean canSeeValues(Authentication a) {
        return a.getAuthorities().stream().anyMatch(g -> g.getAuthority().equals("ROLE_ADMIN") || g.getAuthority().equals("ROLE_OPERATOR"));
    }

    private static String writeDetails(CacheRouter.WriteResult w) {
        return "version=" + w.entry().version() + "; primary=" + w.writtenTo()
                + "; replicaCandidates=" + String.join(",", w.replicas())
                + "; fallbackAttempts=" + w.fallbackAttempts();
    }

    private static boolean isAdmin(Authentication a) {
        return a.getAuthorities().stream().anyMatch(g -> g.getAuthority().equals("ROLE_ADMIN"));
    }

    @PostMapping
    public ResponseEntity<SetResponse> set(@Valid @RequestBody SetRequest req, Authentication auth, HttpServletRequest request) {
        try {
            Long ttlMs = req.ttlSeconds() == null ? null : req.ttlSeconds() * 1000;
            CacheRouter.WriteResult w = rt.router.set(req.key(), req.value(), ttlMs);
            audit.record(auth.getName(), "CACHE_SET", req.key(), "SUCCESS", request.getRemoteAddr(),
                    "Cache entry created.", writeDetails(w));
            return ResponseEntity.status(HttpStatus.CREATED).body(new SetResponse(req.key(), w.entry().version(), w.entry().expiresAt(),
                    w.writtenTo(), w.replicas(), w.fallbackAttempts(),
                    w.fallbackAttempts() > 0 ? "Stored via a replica because the primary did not answer" : "Stored"));
        } catch (RuntimeException e) {
            audit.record(auth.getName(), "CACHE_SET", req.key(), "FAILED: " + e.getMessage(), request.getRemoteAddr(),
                    "Cache SET failed: " + e.getMessage(), null);
            throw e;
        }
    }

    @PutMapping("/{key}")
    public ResponseEntity<SetResponse> update(@PathVariable("key") String key, @Valid @RequestBody UpdateRequest req,
                                              Authentication auth, HttpServletRequest request) {
        try {
            Long ttlMs = req.ttlSeconds() * 1000;
            CacheRouter.WriteResult w = rt.router.update(key, req.value(), ttlMs);
            audit.record(auth.getName(), "CACHE_UPDATE", key, "SUCCESS", request.getRemoteAddr(),
                    "Cache entry updated.", writeDetails(w));
            return ResponseEntity.ok(new SetResponse(key, w.entry().version(), w.entry().expiresAt(), w.writtenTo(), w.replicas(),
                    w.fallbackAttempts(), w.fallbackAttempts() > 0 ? "Updated via replica fallback" : "Updated"));
        } catch (RuntimeException e) {
            audit.record(auth.getName(), "CACHE_UPDATE", key, "FAILED: " + e.getMessage(), request.getRemoteAddr(),
                    "Cache UPDATE failed: " + e.getMessage(), null);
            throw e;
        }
    }

    @GetMapping("/{key}")
    public EntryResponse get(@PathVariable("key") String key, Authentication auth) {
        CacheRouter.ReadResult r = rt.router.get(key);
        if (r.entry() == null) throw new NotFoundException("Key not found: " + key);
        CacheEntry e = r.entry();
        CacheRouter.Placement p = rt.router.locate(key);
        boolean masked = !canSeeValues(auth);
        return new EntryResponse(e.key(), masked ? MASK : e.value(), masked, e.version(), e.createdAt(), e.updatedAt(),
                e.expiresAt(), e.remainingTtlMillis(System.currentTimeMillis()), p.primary(), p.replicas(), r.servedBy());
    }

    /** Explicit cache read result for Explorer: misses return data instead of hiding measured routing in a 404. */
    @GetMapping("/{key}/read")
    public ReadResponse read(@PathVariable("key") String key, Authentication auth) {
        long started = System.nanoTime();
        CacheRouter.ReadResult read = rt.router.get(key);
        long elapsed = System.nanoTime() - started;
        CacheRouter.Placement placement = rt.router.locate(key);
        CacheEntry entry = read.entry();
        boolean masked = !canSeeValues(auth);
        return new ReadResponse(key, entry != null, entry == null || masked ? null : entry.value(), masked,
                entry == null ? 0 : entry.version(), entry == null ? 0 : entry.createdAt(), entry == null ? 0 : entry.updatedAt(),
                entry == null ? 0 : entry.expiresAt(), entry == null ? -2 : entry.remainingTtlMillis(System.currentTimeMillis()),
                read.servedBy(), read.attempts(), elapsed / 1_000_000.0, placement);
    }

    @DeleteMapping("/{key}")
    public Map<String, Object> delete(@PathVariable("key") String key, Authentication auth, HttpServletRequest request) {
        try {
            if (!rt.router.delete(key)) throw new NotFoundException("Key not found: " + key);
            audit.record(auth.getName(), "CACHE_DELETE", key, "SUCCESS", request.getRemoteAddr());
            return Map.of("key", key, "deleted", true);
        } catch (RuntimeException e) {
            audit.record(auth.getName(), "CACHE_DELETE", key, "FAILED: " + e.getMessage(), request.getRemoteAddr());
            throw e;
        }
    }

    @PostMapping("/bulk-delete")
    public Map<String, Object> bulkDelete(@Valid @RequestBody BulkDeleteRequest body, Authentication auth, HttpServletRequest request) {
        List<String> deleted = new java.util.ArrayList<>();
        Map<String, String> failed = new java.util.LinkedHashMap<>();
        for (String key : body.keys().stream().distinct().toList()) {
            try { if (rt.router.delete(key)) deleted.add(key); else failed.put(key, "Key no longer exists"); }
            catch (RuntimeException e) { failed.put(key, String.valueOf(e.getMessage())); }
        }
        audit.record(auth.getName(), "CACHE_BULK_DELETE", null,
                "deleted=" + deleted.size() + ", failed=" + failed.size(), request.getRemoteAddr());
        return Map.of("deleted", deleted, "failed", failed);
    }

    @DeleteMapping("/clear")
    public Map<String, Object> clear(Authentication auth, HttpServletRequest request) {
        List<String> deleted = new java.util.ArrayList<>();
        Map<String, String> failed = new java.util.LinkedHashMap<>();
        for (CacheEntry entry : rt.router.searchKeys("", Integer.MAX_VALUE)) {
            try { if (rt.router.delete(entry.key())) deleted.add(entry.key()); }
            catch (RuntimeException e) { failed.put(entry.key(), String.valueOf(e.getMessage())); }
        }
        audit.record(auth.getName(), "CACHE_CLEAR", null,
                "deleted=" + deleted.size() + ", failed=" + failed.size(), request.getRemoteAddr());
        return Map.of("deleted", deleted, "failed", failed);
    }

    @GetMapping("/{key}/exists")
    public Map<String, Object> exists(@PathVariable("key") String key) {
        return Map.of("key", key, "exists", rt.router.exists(key));
    }

    @GetMapping("/{key}/ttl")
    public Map<String, Object> ttl(@PathVariable("key") String key) {
        long ms = rt.router.ttl(key);
        if (ms == -2) throw new NotFoundException("Key not found: " + key);
        return Map.of("key", key, "hasExpiry", ms >= 0, "ttlMillis", ms, "ttlSeconds", ms < 0 ? -1 : (ms + 999) / 1000);
    }

    @PostMapping("/{key}/expire")
    public Map<String, Object> expire(@PathVariable("key") String key, @Valid @RequestBody ExpireRequest req) {
        CacheEntry e = rt.router.expire(key, req.ttlSeconds() * 1000);
        if (e == null) throw new NotFoundException("Key not found: " + key);
        return Map.of("key", key, "version", e.version(), "expiresAt", e.expiresAt(),
                "message", req.ttlSeconds() == 0 ? "Expiry removed" : "TTL set to " + req.ttlSeconds() + "s");
    }

    @GetMapping("/{key}/locate")
    public CacheRouter.Placement locate(@PathVariable("key") String key) { return rt.router.locate(key); }

    /** Cache Explorer: search keys by prefix. Values are masked unless an ADMIN explicitly asks to reveal them. */
    @GetMapping("/keys")
    public List<KeyInfo> keys(@RequestParam(name = "prefix", defaultValue = "") String prefix,
                              @RequestParam(name = "limit", defaultValue = "50") @Min(1) @Max(500) int limit,
                              @RequestParam(name = "reveal", defaultValue = "false") boolean reveal,
                              @RequestParam(name = "offset", defaultValue = "0") @Min(0) int offset,
                              @RequestParam(name = "primaryNode", required = false) String primaryNode,
                              @RequestParam(name = "replicaNode", required = false) String replicaNode,
                              @RequestParam(name = "dataType", defaultValue = "STRING") String dataType,
                              @RequestParam(name = "expiration", defaultValue = "ALL") String expiration,
                              @RequestParam(name = "expiringWithinSeconds", defaultValue = "300") @Min(1) @Max(2_592_000) long expiringWithinSeconds,
                              @RequestParam(name = "sortBy", defaultValue = "KEY") String sortBy,
                              @RequestParam(name = "descending", defaultValue = "false") boolean descending,
                              Authentication auth) {
        long now = System.currentTimeMillis();
        if (!"STRING".equalsIgnoreCase(dataType)) throw new com.distributedcache.exception.InvalidRequestException("Only STRING cache values are supported.");
        Comparator<CacheEntry> comparator = switch (sortBy.toUpperCase()) {
            case "SIZE" -> Comparator.comparingLong(CacheEntry::approxBytes);
            case "TTL" -> Comparator.comparingLong(e -> e.remainingTtlMillis(now) < 0 ? Long.MAX_VALUE : e.remainingTtlMillis(now));
            case "KEY" -> Comparator.comparing(CacheEntry::key);
            default -> throw new com.distributedcache.exception.InvalidRequestException("sortBy must be KEY, SIZE, or TTL.");
        };
        if (descending) comparator = comparator.reversed();
        List<CacheEntry> filtered = rt.router.searchKeys(prefix, Integer.MAX_VALUE).stream()
                .filter(e -> {
                    CacheRouter.Placement placement = rt.router.locate(e.key());
                    if (primaryNode != null && !primaryNode.isBlank() && !primaryNode.equals(placement.primary())) return false;
                    if (replicaNode != null && !replicaNode.isBlank() && !placement.replicas().contains(replicaNode)) return false;
                    long ttl = e.remainingTtlMillis(now);
                    return switch (expiration.toUpperCase()) {
                        case "ALL" -> true;
                        case "HAS_EXPIRY" -> ttl >= 0;
                        case "NO_EXPIRY" -> ttl < 0;
                        case "EXPIRING" -> ttl >= 0 && ttl <= expiringWithinSeconds * 1000;
                        default -> throw new com.distributedcache.exception.InvalidRequestException("expiration must be ALL, HAS_EXPIRY, NO_EXPIRY, or EXPIRING.");
                    };
                }).sorted(comparator).toList();
        if (offset >= filtered.size()) return List.of();
        return filtered.subList(offset, (int) Math.min(filtered.size(), (long) offset + limit)).stream().map(e -> {
            CacheRouter.Placement p = rt.router.locate(e.key());
            boolean sensitive = SENSITIVE.matcher(e.key()).matches();
            boolean show = reveal && isAdmin(auth) || (!sensitive && canSeeValues(auth) && reveal);
            String v = e.value() == null ? "" : e.value();
            String preview = show ? (v.length() > 80 ? v.substring(0, 80) + "…" : v) : MASK;
            return new KeyInfo(e.key(), preview, !show, e.version(), e.createdAt(), e.updatedAt(), e.expiresAt(),
                    e.remainingTtlMillis(now), e.approxBytes(), "STRING", p.primary(), p.replicas(), p.ringPosition());
        }).toList();
    }
}
