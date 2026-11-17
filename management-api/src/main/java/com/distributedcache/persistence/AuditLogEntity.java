package com.distributedcache.persistence;

import jakarta.persistence.*;

@Entity
@Table(name = "audit_logs")
public class AuditLogEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(nullable = false) public String username;
    @Column(nullable = false) public String action;
    public String target;
    @Column(length = 1000) public String description;
    @Column(length = 4000) public String details;
    @Column(nullable = false) public String result;
    public String ipAddress;
    @Column(nullable = false) public long createdAt;
}
