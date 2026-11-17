package com.distributedcache.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "app.security")
public class SecurityProperties {
    private String jwtSecret;
    private long jwtTtlMinutes = 60;
    private List<String> allowedOrigins = List.of("http://localhost:5173");
    private String adminPassword;
    private String operatorPassword;
    private String viewerPassword;

    public String getJwtSecret() { return jwtSecret; }
    public void setJwtSecret(String v) { this.jwtSecret = v; }
    public long getJwtTtlMinutes() { return jwtTtlMinutes; }
    public void setJwtTtlMinutes(long v) { this.jwtTtlMinutes = v; }
    public List<String> getAllowedOrigins() { return allowedOrigins; }
    public void setAllowedOrigins(List<String> v) { this.allowedOrigins = v; }
    public String getAdminPassword() { return adminPassword; }
    public void setAdminPassword(String v) { this.adminPassword = v; }
    public String getOperatorPassword() { return operatorPassword; }
    public void setOperatorPassword(String v) { this.operatorPassword = v; }
    public String getViewerPassword() { return viewerPassword; }
    public void setViewerPassword(String v) { this.viewerPassword = v; }
}
