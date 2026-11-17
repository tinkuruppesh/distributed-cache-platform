package com.distributedcache.api.simulation;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Admin-only by the existing POST /api/v1/simulations/** Spring Security rule. */
@RestController
@RequestMapping("/api/v1/simulations")
public class SimulationHistoryImportController {
    private final SimulationHistoryImportService importer;

    public SimulationHistoryImportController(SimulationHistoryImportService importer) {
        this.importer = importer;
    }

    @PostMapping("/import")
    @ResponseStatus(HttpStatus.CREATED)
    public SimulationController.Detail importHistory(@RequestBody SimulationController.Detail detail) {
        return importer.importCancelled(detail);
    }
}
