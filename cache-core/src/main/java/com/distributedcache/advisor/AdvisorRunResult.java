package com.distributedcache.advisor;

import java.util.List;

/** Result of evaluating measured advisor inputs; created remains the count of newly opened findings. */
public record AdvisorRunResult(boolean successful, int created, int evaluatedRules,
                               List<RuleFailure> ruleFailures, String error) {
    public AdvisorRunResult {
        ruleFailures = List.copyOf(ruleFailures);
    }

    public record RuleFailure(String rule, String message) {}
}
