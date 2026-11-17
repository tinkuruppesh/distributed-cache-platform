package com.distributedcache.advisor;

/** What a rule reports; the store turns findings into tracked Recommendations. */
public record Finding(String dedupeKey, Recommendation.Category category, Recommendation.Severity severity,
                      String title, String description, String supportingMetric, String reason, String recommendedAction) {}
