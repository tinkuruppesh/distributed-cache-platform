package com.distributedcache.security;

import com.distributedcache.audit.AuditFilter;
import com.distributedcache.audit.AuditService;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Stateless JWT security with role-based authorization.
 * Rule of thumb: reading = any authenticated role, data writes = OPERATOR+, cluster/simulation/settings = ADMIN.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

    private static SecretKey key(SecurityProperties p) {
        byte[] bytes = p.getJwtSecret().getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) throw new IllegalStateException("app.security.jwt-secret must be at least 32 characters");
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder(SecurityProperties p) { return new NimbusJwtEncoder(new ImmutableSecret<>(key(p))); }

    @Bean
    public JwtDecoder jwtDecoder(SecurityProperties p) {
        return NimbusJwtDecoder.withSecretKey(key(p)).macAlgorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter g = new JwtGrantedAuthoritiesConverter();
        g.setAuthoritiesClaimName("roles");
        g.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter c = new JwtAuthenticationConverter();
        c.setJwtGrantedAuthoritiesConverter(g);
        return c;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(SecurityProperties p) {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(p.getAllowedOrigins());
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        UrlBasedCorsConfigurationSource s = new UrlBasedCorsConfigurationSource();
        s.registerCorsConfiguration("/**", c);
        return s;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, AuditService audit, JwtAuthenticationConverter conv) throws Exception {
        http
            .csrf(csrf -> csrf.disable())                                   // stateless API: no cookies, so no CSRF surface
            .cors(Customizer.withDefaults())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, ex) -> json(res, 401, "Authentication required"))
                .accessDeniedHandler((req, res, ex) -> json(res, 403, "You do not have permission for this action")))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/api/v1/auth/login", "/actuator/health", "/actuator/health/**", "/actuator/info",
                        "/actuator/prometheus").permitAll()
                .requestMatchers(HttpMethod.GET, "/actuator/metrics", "/actuator/metrics/**").hasAnyRole("ADMIN", "OPERATOR")
                // control plane
                .requestMatchers(HttpMethod.GET, "/api/v1/audit-logs/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/v1/settings").hasAnyRole("ADMIN", "OPERATOR")
                .requestMatchers(HttpMethod.PUT, "/api/v1/settings").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/simulations/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/cluster/nodes").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/v1/cluster/nodes/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/cluster/nodes/*/maintenance", "/api/v1/cluster/nodes/*/resume")
                    .hasAnyRole("ADMIN", "OPERATOR")
                .requestMatchers(HttpMethod.POST, "/api/v1/advisor/**").hasAnyRole("ADMIN", "OPERATOR")
                // data plane
                .requestMatchers(HttpMethod.POST, "/api/v1/cache/**").hasAnyRole("ADMIN", "OPERATOR")
                .requestMatchers(HttpMethod.PUT, "/api/v1/cache/**").hasAnyRole("ADMIN", "OPERATOR")
                .requestMatchers(HttpMethod.DELETE, "/api/v1/cache/**").hasAnyRole("ADMIN", "OPERATOR")
                // everything else that is a read
                .requestMatchers(HttpMethod.GET, "/api/v1/**").hasAnyRole("ADMIN", "OPERATOR", "VIEWER")
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout").authenticated()
                .anyRequest().denyAll())
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(conv)))
            .addFilterAfter(new AuditFilter(audit), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    private static void json(HttpServletResponse res, int status, String message) throws java.io.IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.getWriter().write("{\"status\":" + status + ",\"message\":\"" + message + "\"}");
    }
}
