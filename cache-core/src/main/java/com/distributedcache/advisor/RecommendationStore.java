package com.distributedcache.advisor;

import com.distributedcache.advisor.Recommendation.Status;
import com.distributedcache.exception.NotFoundException;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Tracks recommendation history and status (OPEN / ACKNOWLEDGED / RESOLVED / DISMISSED). */
public final class RecommendationStore {
    private static final long COOLDOWN_MS = 10 * 60 * 1000L;
    private final ConcurrentHashMap<String, Recommendation> byId = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<Recommendation>> listeners = new CopyOnWriteArrayList<>();

    public void addListener(Consumer<Recommendation> l) { listeners.add(l); }

    /** Used at startup to reload persisted history. */
    public void load(Collection<Recommendation> recs) { for (Recommendation r : recs) byId.put(r.id(), r); }

    public synchronized int upsert(List<Finding> findings) {
        long now = System.currentTimeMillis();
        int created = 0;
        for (Finding f : findings) {
            Recommendation active = null, lastClosed = null;
            for (Recommendation r : byId.values()) {
                if (!r.dedupeKey().equals(f.dedupeKey())) continue;
                if (r.status() == Status.OPEN || r.status() == Status.ACKNOWLEDGED) active = r;
                else if (lastClosed == null || r.updatedAt() > lastClosed.updatedAt()) lastClosed = r;
            }
            if (active != null) {   // refresh the evidence, keep the workflow status
                Recommendation u = new Recommendation(active.id(), active.dedupeKey(), f.category(), f.severity(), f.title(),
                        f.description(), f.supportingMetric(), f.reason(), f.recommendedAction(), active.createdAt(), now, active.status());
                byId.put(u.id(), u);
                notifyAll(u);
            } else if (lastClosed == null || now - lastClosed.updatedAt() > COOLDOWN_MS) {
                Recommendation n = new Recommendation(UUID.randomUUID().toString().substring(0, 8), f.dedupeKey(), f.category(),
                        f.severity(), f.title(), f.description(), f.supportingMetric(), f.reason(), f.recommendedAction(),
                        now, now, Status.OPEN);
                byId.put(n.id(), n);
                notifyAll(n);
                created++;
            }
        }
        return created;
    }

    /** Resolve active records only for conditions a successfully evaluated rule explicitly owns. */
    public synchronized int resolveAbsentConditions(Collection<String> evaluatedConditions, Collection<String> currentFindings) {
        Set<String> evaluated = Set.copyOf(evaluatedConditions);
        Set<String> current = Set.copyOf(currentFindings);
        int resolved = 0;
        for (Recommendation recommendation : byId.values()) {
            if (!evaluated.contains(recommendation.dedupeKey()) || current.contains(recommendation.dedupeKey())) continue;
            if (recommendation.status() != Status.OPEN && recommendation.status() != Status.ACKNOWLEDGED) continue;
            Recommendation updated = recommendation.withStatus(Status.RESOLVED);
            byId.put(updated.id(), updated);
            notifyAll(updated);
            resolved++;
        }
        return resolved;
    }

    private void notifyAll(Recommendation r) {
        for (Consumer<Recommendation> l : listeners) { try { l.accept(r); } catch (RuntimeException ignored) {} }
    }

    public List<Recommendation> list(Status status, Recommendation.Category category) {
        List<Recommendation> l = new ArrayList<>();
        for (Recommendation r : byId.values()) {
            if (status != null && r.status() != status) continue;
            if (category != null && r.category() != category) continue;
            l.add(r);
        }
        l.sort((a, b) -> Long.compare(b.createdAt(), a.createdAt()));
        return l;
    }

    public Recommendation get(String id) {
        Recommendation r = byId.get(id);
        if (r == null) throw new NotFoundException("Unknown recommendation: " + id);
        return r;
    }

    public synchronized Recommendation setStatus(String id, Status s) {
        Recommendation u = get(id).withStatus(s);
        byId.put(id, u);
        notifyAll(u);
        return u;
    }
}
