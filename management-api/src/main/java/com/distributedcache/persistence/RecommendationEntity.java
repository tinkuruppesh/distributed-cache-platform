package com.distributedcache.persistence;

import jakarta.persistence.*;

@Entity
@Table(name = "recommendations")
public class RecommendationEntity {
    @Id public String id;
    @Column(nullable = false) public String dedupeKey;
    @Column(nullable = false) public String category;
    @Column(nullable = false) public String severity;
    @Column(nullable = false) public String title;
    @Column(length = 2000) public String description;
    @Column(length = 1000) public String supportingMetric;
    @Column(length = 2000) public String reason;
    @Column(length = 2000) public String recommendedAction;
    @Column(nullable = false) public String status;
    @Column(nullable = false) public long createdAt;
    @Column(nullable = false) public long updatedAt;
}
