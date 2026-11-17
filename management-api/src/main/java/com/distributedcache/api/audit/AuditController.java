package com.distributedcache.api.audit;

import com.distributedcache.audit.AuditService;
import com.distributedcache.persistence.AuditLogEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/audit-logs")
@Validated
public class AuditController {
    private final AuditService audit;

    public AuditController(AuditService audit) { this.audit = audit; }

    @GetMapping
    public Map<String, Object> list(@RequestParam(name = "q", required = false) String q,
                                    @RequestParam(name = "action", required = false) String action,
                                    @RequestParam(name = "status", required = false) String status,
                                    @RequestParam(name = "from", required = false) Long from,
                                    @RequestParam(name = "to", required = false) Long to,
                                    @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
                                    @RequestParam(name = "size", defaultValue = "25") @Min(1) @Max(200) int size) {
        var p = audit.search(q, action, status, from, to, page, size);
        List<AuditLogEntity> items = p.getContent();
        return Map.of("items", items, "page", p.getNumber(), "totalPages", p.getTotalPages(), "totalItems", p.getTotalElements());
    }
}
