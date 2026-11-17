package com.distributedcache.advisor;

import java.util.List;
import java.util.Set;
import java.util.Set;

@FunctionalInterface
public interface RecommendationRule {
    List<Finding> evaluate(AdvisorSnapshot s);

    /** Conditions this rule fully evaluated from the snapshot, whether or not they produced a finding. */
    default Set<String> fullyEvaluatedConditions(AdvisorSnapshot s) { return Set.of(); }

    /** Conditions this rule fully evaluated from the snapshot, whether or not they produced a finding. */
    default Set<String> fullyEvaluatedConditions(AdvisorSnapshot s) { return Set.of(); }

    /** Stable diagnostic name; built-in rules override this via their named wrappers. */
    default String name() { return getClass().getSimpleName().isBlank() ? getClass().getName() : getClass().getSimpleName(); }
}
