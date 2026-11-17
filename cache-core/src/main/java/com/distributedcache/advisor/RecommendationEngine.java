package com.distributedcache.advisor;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

public final class RecommendationEngine {
    private static final System.Logger LOG = System.getLogger(RecommendationEngine.class.getName());
    private final List<RecommendationRule> rules;
    private final RecommendationStore store;

    public RecommendationEngine(List<RecommendationRule> rules, RecommendationStore store) {
        this.rules = rules; this.store = store;
    }

    /** Evaluates all rules against measured data and reports individual rule failures. */
    public AdvisorRunResult run(AdvisorSnapshot snapshot) {
        List<Finding> all = new ArrayList<>();
        List<AdvisorRunResult.RuleFailure> failures = new ArrayList<>();
        Set<String> evaluatedConditions = new LinkedHashSet<>();
        for (RecommendationRule rule : rules) {
            try {
                all.addAll(rule.evaluate(snapshot));
                evaluatedConditions.addAll(rule.fullyEvaluatedConditions(snapshot));
            } catch (RuntimeException ex) {
                String message = ex.getMessage() == null || ex.getMessage().isBlank()
                        ? ex.getClass().getSimpleName() : ex.getMessage();
                failures.add(new AdvisorRunResult.RuleFailure(rule.name(), message));
                LOG.log(System.Logger.Level.ERROR, "Advisor rule " + rule.name() + " failed", ex);
            }
        }
        int created = store.upsert(all);
        // Only conditions declared by rules that completed successfully can close active history.
        store.resolveAbsentConditions(evaluatedConditions, all.stream().map(Finding::dedupeKey).toList());
        return new AdvisorRunResult(failures.isEmpty(), created, rules.size(), failures, null);
    }
}
