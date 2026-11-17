package com.distributedcache.advisor;

public record Recommendation(String id, String dedupeKey, Category category, Severity severity, String title,
                             String description, String supportingMetric, String reason, String recommendedAction,
                             long createdAt, long updatedAt, Status status) {
    public enum Category { PERFORMANCE, MEMORY, SCALABILITY, REPLICATION, HOT_KEYS, CONFIGURATION }
    public enum Severity { INFO, WARNING, CRITICAL }
    public enum Status { OPEN, ACKNOWLEDGED, RESOLVED, DISMISSED }

    public Recommendation withStatus(Status s) {
        return new Recommendation(id, dedupeKey, category, severity, title, description, supportingMetric, reason,
                recommendedAction, createdAt, System.currentTimeMillis(), s);
    }
}
