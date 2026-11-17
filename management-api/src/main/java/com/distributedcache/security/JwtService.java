package com.distributedcache.security;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Issues signed (HS256) JWT access tokens. Verification is done by Spring's resource-server JwtDecoder. */
@Service
public class JwtService {
    public record Token(String value, Instant expiresAt) {}

    private final JwtEncoder encoder;
    private final Duration ttl;

    public JwtService(JwtEncoder encoder, SecurityProperties props) {
        this.encoder = encoder;
        this.ttl = Duration.ofMinutes(props.getJwtTtlMinutes());
    }

    public Token issue(String username, String role) {
        Instant now = Instant.now();
        Instant exp = now.plus(ttl);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("distributed-cache-platform")
                .subject(username)
                .issuedAt(now)
                .expiresAt(exp)
                .claim("roles", List.of(role))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return new Token(encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue(), exp);
    }
}
