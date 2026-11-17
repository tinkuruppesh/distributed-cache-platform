package com.distributedcache.persistence;

import jakarta.persistence.*;

@Entity
@Table(name = "failover_history")
public class FailoverHistoryEntity {
    @Id public String id;
    @Column(nullable = false) public String nodeId;
    @Column(nullable = false) public long detectedAt;
    @Column(nullable = false) public long failoverStartedAt;
    @Column(nullable = false) public long failoverCompletedAt;
    @Column(nullable = false) public int keysPromoted;
    @Column(nullable = false) public int reReplicated;
    @Column(nullable = false) public String triggerType;
    @Column(nullable = false) public long createdAt;
}
