package com.distributedcache.api.auth;

import com.distributedcache.audit.AuditService;
import com.distributedcache.persistence.UserAccount;
import com.distributedcache.persistence.UserRepository;
import com.distributedcache.security.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
    public record LoginResponse(String token, String tokenType, Instant expiresAt, String username, String role) {}

    private static final int MAX_FAILURES = 5;
    private static final long WINDOW_MS = 5 * 60_000L;
    private record Attempts(int count, long firstAt) {}

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final AuditService audit;
    private final Map<String, Attempts> failures = new ConcurrentHashMap<>();

    public AuthController(UserRepository users, PasswordEncoder encoder, JwtService jwt, AuditService audit) {
        this.users = users; this.encoder = encoder; this.jwt = jwt; this.audit = audit;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        String ip = http.getRemoteAddr();
        String throttleKey = req.username().toLowerCase() + "|" + ip;
        Attempts a = failures.get(throttleKey);
        long now = System.currentTimeMillis();
        if (a != null && now - a.firstAt() < WINDOW_MS && a.count() >= MAX_FAILURES) {
            audit.record(req.username(), "LOGIN", null, "THROTTLED", ip);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("status", 429, "message", "Too many failed attempts. Try again in a few minutes."));
        }
        UserAccount u = users.findByUsername(req.username()).orElse(null);
        boolean ok = u != null && u.enabled && encoder.matches(req.password(), u.passwordHash);
        if (!ok) {
            failures.merge(throttleKey, new Attempts(1, now), (old, n) ->
                    now - old.firstAt() > WINDOW_MS ? n : new Attempts(old.count() + 1, old.firstAt()));
            audit.record(req.username(), "LOGIN", null, "FAILED", ip);
            // same message whether the user exists or not (no user enumeration)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("status", 401, "message", "Invalid username or password"));
        }
        failures.remove(throttleKey);
        JwtService.Token t = jwt.issue(u.username, u.role);
        audit.record(u.username, "LOGIN", null, "SUCCESS", ip);
        return ResponseEntity.ok(new LoginResponse(t.value(), "Bearer", t.expiresAt(), u.username, u.role));
    }

    /** Tokens are stateless; logout is recorded for the audit trail and the client discards its token. */
    @PostMapping("/logout")
    public Map<String, String> logout(Authentication auth, HttpServletRequest http) {
        audit.record(auth.getName(), "LOGOUT", null, "SUCCESS", http.getRemoteAddr());
        return Map.of("message", "Logged out. Discard the token on the client.");
    }

    @GetMapping("/me")
    public Map<String, Object> me(Authentication auth) {
        return Map.of("username", auth.getName(),
                "roles", auth.getAuthorities().stream().map(g -> g.getAuthority().replace("ROLE_", "")).toList());
    }
}
