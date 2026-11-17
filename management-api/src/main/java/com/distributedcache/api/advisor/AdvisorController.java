package com.distributedcache.api.advisor;

import com.distributedcache.advisor.Recommendation;
import com.distributedcache.advisor.AdvisorRunResult;
import com.distributedcache.advisor.Recommendation.Category;
import com.distributedcache.advisor.Recommendation.Status;
import com.distributedcache.cluster.ClusterRuntime;
import com.distributedcache.exception.InvalidRequestException;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/advisor")
public class AdvisorController {
    private final ClusterRuntime rt;

    public AdvisorController(ClusterRuntime rt) { this.rt = rt; }

    @GetMapping("/recommendations")
    public List<Recommendation> list(@RequestParam(name = "status", required = false) String status,
                                    @RequestParam(name = "category", required = false) String category) {
        return rt.recommendations.list(parse(Status.class, status), parse(Category.class, category));
    }

    @GetMapping("/recommendations/{id}")
    public Recommendation get(@PathVariable("id") String id) { return rt.recommendations.get(id); }

    @PostMapping("/recommendations/{id}/acknowledge")
    public Recommendation acknowledge(@PathVariable("id") String id) { return rt.recommendations.setStatus(id, Status.ACKNOWLEDGED); }

    @PostMapping("/recommendations/{id}/resolve")
    public Recommendation resolve(@PathVariable("id") String id) { return rt.recommendations.setStatus(id, Status.RESOLVED); }

    @PostMapping("/recommendations/{id}/dismiss")
    public Recommendation dismiss(@PathVariable("id") String id) { return rt.recommendations.setStatus(id, Status.DISMISSED); }

    /** Re-evaluates the rules right now against the current measurements. */
    @PostMapping("/run")
    public AdvisorRunResult run() { return rt.runAdvisor(); }

    private static <E extends Enum<E>> E parse(Class<E> type, String v) {
        if (v == null || v.isBlank()) return null;
        try { return Enum.valueOf(type, v.toUpperCase()); }
        catch (IllegalArgumentException e) { throw new InvalidRequestException("Invalid " + type.getSimpleName().toLowerCase() + ": " + v); }
    }
}
