package com.distributedcache.unit;

import com.distributedcache.advisor.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RecommendationEngineTest {
    @Test
    void reportsRuleFailureAndContinuesEvaluatingOtherRules() {
        RecommendationRule failing = new RecommendationRule() {
            @Override public List<Finding> evaluate(AdvisorSnapshot snapshot) { throw new IllegalStateException("bad measurement"); }
            @Override public String name() { return "broken-test-rule"; }
        };
        RecommendationRule successful = snapshot -> List.of(new Finding("test", Recommendation.Category.PERFORMANCE,
                Recommendation.Severity.INFO, "title", "description", "metric", "reason", "action"));
        RecommendationStore store = new RecommendationStore();
        RecommendationEngine engine = new RecommendationEngine(List.of(failing, successful), store);

        AdvisorRunResult result = engine.run(emptySnapshot());

        assertFalse(result.successful());
        assertEquals(1, result.created());
        assertEquals(2, result.evaluatedRules());
        assertEquals(1, result.ruleFailures().size());
        assertEquals("broken-test-rule", result.ruleFailures().getFirst().rule());
        assertEquals("bad measurement", result.ruleFailures().getFirst().message());
        assertNull(result.error());
        assertEquals(1, store.list(null, null).size(), "the healthy rule still persists its finding");
    }

    @Test
    void resolvesOnlyExplicitlyEvaluatedScalabilityConditionsWhenTheyAreNoLongerPresent() {
        RecommendationStore store = new RecommendationStore();
        RecommendationRule scalability = BuiltInRules.all().stream()
                .filter(rule -> rule.name().equals("scalability")).findFirst().orElseThrow();
        RecommendationEngine engine = new RecommendationEngine(List.of(scalability), store);

        engine.run(snapshotWithNodes(5, 6));
        Recommendation active = store.list(Recommendation.Status.OPEN, null).getFirst();
        assertEquals("reduced-capacity", active.dedupeKey());

        engine.run(snapshotWithNodes(6, 6));
        assertEquals(Recommendation.Status.RESOLVED, store.get(active.id()).status());
    }

    @Test
    void doesNotResolveConditionWhenOwningRuleFailsOrWhenConditionWasDismissed() {
        RecommendationStore store = new RecommendationStore();
        store.upsert(List.of(new Finding("reduced-capacity", Recommendation.Category.SCALABILITY,
                Recommendation.Severity.WARNING, "title", "description", "metric", "reason", "action")));
        Recommendation record = store.list(Recommendation.Status.OPEN, null).getFirst();
        RecommendationRule failing = new RecommendationRule() {
            @Override public List<Finding> evaluate(AdvisorSnapshot snapshot) { throw new IllegalStateException("incomplete data"); }
            @Override public String name() { return "scalability"; }
            @Override public Set<String> fullyEvaluatedConditions(AdvisorSnapshot snapshot) { return Set.of("reduced-capacity"); }
        };

        new RecommendationEngine(List.of(failing), store).run(emptySnapshot());
        assertEquals(Recommendation.Status.OPEN, store.get(record.id()).status());
        store.setStatus(record.id(), Recommendation.Status.DISMISSED);
        store.resolveAbsentConditions(Set.of("reduced-capacity"), List.of());
        assertEquals(Recommendation.Status.DISMISSED, store.get(record.id()).status());
    }

    private static AdvisorSnapshot snapshotWithNodes(int activeNodes, int totalNodes) {
        return new AdvisorSnapshot(List.of(), 0, 0, 0, 0, 0, 0, List.of(),
                0, 0, 0, 0, 0, activeNodes, totalNodes, 2, 1000, 5000, 0, 0, 0, 5000, false);
    }

    private static AdvisorSnapshot emptySnapshot() {
        return new AdvisorSnapshot(List.of(), 0, 0, 0, 0, 0, 0, List.of(),
                0, 0, 0, 0, 0, 0, 0, 0, 1000, 5000, 0, 0, 0, 5000, false);
    }
}
