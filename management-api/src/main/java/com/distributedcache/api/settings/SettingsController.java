package com.distributedcache.api.settings;

import com.distributedcache.cluster.ClusterRuntime;
import com.distributedcache.cluster.ClusterSettings;
import com.distributedcache.persistence.PersistenceBridge;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {
    private final ClusterRuntime rt;
    private final PersistenceBridge persistence;

    public SettingsController(ClusterRuntime rt, PersistenceBridge persistence) { this.rt = rt; this.persistence = persistence; }

    @GetMapping
    public ClusterSettings get() { return rt.settings(); }

    /** Validated first (400 on any invalid value, nothing changes), applied second, then persisted. */
    @PutMapping
    public ClusterSettings update(@RequestBody ClusterSettings settings) {
        ClusterSettings applied = rt.applySettings(settings);
        persistence.saveSettings(applied);
        return applied;
    }
}
