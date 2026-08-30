package com.adam.server.ops;

import com.adam.server.persistence.FeatureFlagEntity;
import com.adam.server.persistence.FeatureFlagRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime toggles for the live HTS pipeline (E-6), editable from the admin panel
 * without a dyno restart. Only HTS flags exist — SDD-M15 / SDD-SWING are retired
 * and their schedulers are gated by plain env vars (default off).
 *
 * <p><b>Fail toward ON.</b> Every flag here defaults to {@code true}: a missing
 * DB row, a failed refresh, or an unknown key must never silently disarm the
 * scan / execution / monitor. A DB override (set from the panel) wins and, since
 * the fix in this class, survives a restart. To stop something you must
 * explicitly toggle it off — it will not turn itself off.
 */
@Service
public class FeatureFlags {

    private static final Logger log = LoggerFactory.getLogger(FeatureFlags.class);

    /** flag name → the env property that supplies its default, in display order. */
    private static final Map<String, String> KEY = new LinkedHashMap<>();
    /** flag name → human description for the admin panel. */
    private static final Map<String, String> DESC = new LinkedHashMap<>();
    /** flag name → hard fallback when everything else is missing (all true — fail toward ON). */
    private static final Map<String, Boolean> FALLBACK = new LinkedHashMap<>();

    static {
        def("hts.scan", "app.hts.scan-enabled", "Skan HTS (wszystkie warianty)");
        def("hts.execution", "app.hts.execution-enabled", "Egzekucja HTS na kontach demo");
        def("hts.live-execution", "app.hts.live-execution-enabled", "Egzekucja HTS CORE_LIVE (realne)");
        def("hts.monitor", "app.hts.monitor-enabled", "Monitor pozycji HTS (runner exit)");
        def("hts.weekend-flatten", "app.hts.weekend-flatten-enabled",
                "Zamknij pozycje FAST (konto m5) w piątek wieczorem — bez BTC");
    }

    private static void def(String name, String prop, String desc) {
        KEY.put(name, prop);
        FALLBACK.put(name, true);
        DESC.put(name, desc);
    }

    private final FeatureFlagRepository repo;
    private final Map<String, Boolean> defaults = new LinkedHashMap<>();
    private final Map<String, Boolean> overrides = new ConcurrentHashMap<>();

    /**
     * The single constructor — {@code @Autowired} so Spring never falls back to a
     * no-arg one (the earlier regression: a private no-arg constructor added for
     * tests made Spring skip this constructor entirely, so {@code repo} was null,
     * nothing persisted, and every restart wiped the panel toggles).
     *
     * <p>Defaults resolve from {@code @Value} — nested {@code ${ENV:default}} and
     * all — exactly as the schedulers/gates did before E-6.
     */
    @Autowired
    public FeatureFlags(
            FeatureFlagRepository repo,
            @Value("${app.hts.scan-enabled:true}") boolean htsScan,
            @Value("${app.hts.execution-enabled:true}") boolean htsExecution,
            @Value("${app.hts.live-execution-enabled:true}") boolean htsLiveExecution,
            @Value("${app.hts.monitor-enabled:true}") boolean htsMonitor,
            @Value("${app.hts.weekend-flatten-enabled:true}") boolean htsWeekendFlatten
    ) {
        this.repo = repo;
        defaults.put("hts.scan", htsScan);
        defaults.put("hts.execution", htsExecution);
        defaults.put("hts.live-execution", htsLiveExecution);
        defaults.put("hts.monitor", htsMonitor);
        defaults.put("hts.weekend-flatten", htsWeekendFlatten);
        log.info("Feature flag env defaults: {}", defaults);
        refresh();
    }

    /** Test instance — no DB. {@link #set}/{@link #reset} only move the in-memory cache. */
    public static FeatureFlags forTest() {
        return new FeatureFlags(null, true, true, true, true, true);
    }

    /** Effective value: DB override if set, otherwise the env default, otherwise true. */
    public boolean enabled(String name) {
        Boolean o = overrides.get(name);
        if (o != null) {
            return o;
        }
        Boolean d = defaults.get(name);
        return d != null ? d : FALLBACK.getOrDefault(name, false);
    }

    public boolean isKnown(String name) {
        return KEY.containsKey(name);
    }

    @Scheduled(fixedDelayString = "${app.ops.flags-refresh-ms:30000}")
    public void refresh() {
        if (repo == null) {
            return;
        }
        try {
            Map<String, Boolean> fresh = new ConcurrentHashMap<>();
            for (FeatureFlagEntity f : repo.findAll()) {
                if (KEY.containsKey(f.getName())) {
                    fresh.put(f.getName(), f.isEnabled());
                }
            }
            overrides.keySet().retainAll(fresh.keySet());
            overrides.putAll(fresh);
        } catch (Exception e) {
            log.warn("Feature flag refresh failed: {}", e.getClass().getSimpleName());
        }
    }

    @Transactional
    public void set(String name, boolean value, String by) {
        if (!isKnown(name)) {
            throw new IllegalArgumentException("unknown flag: " + name);
        }
        if (repo != null) {
            FeatureFlagEntity row = repo.findByName(name).orElseGet(() -> {
                FeatureFlagEntity e = new FeatureFlagEntity();
                e.setName(name);
                return e;
            });
            row.setEnabled(value);
            row.setUpdatedAt(Instant.now());
            row.setUpdatedBy(by);
            repo.save(row);
        }
        overrides.put(name, value);
        log.info("Feature flag {} set to {} by {}", name, value, by);
    }

    /** Drop the override — revert to the env default. */
    @Transactional
    public void reset(String name) {
        if (!isKnown(name)) {
            throw new IllegalArgumentException("unknown flag: " + name);
        }
        if (repo != null) {
            repo.deleteByName(name);
        }
        overrides.remove(name);
        log.info("Feature flag {} reset to env default", name);
    }

    public List<FlagView> list() {
        Map<String, FeatureFlagEntity> rows = new LinkedHashMap<>();
        if (repo != null) {
            for (FeatureFlagEntity f : repo.findAllByOrderByNameAsc()) {
                rows.put(f.getName(), f);
            }
        }
        List<FlagView> out = new ArrayList<>();
        for (String name : KEY.keySet()) {
            FeatureFlagEntity r = rows.get(name);
            boolean envDefault = defaults.getOrDefault(name, FALLBACK.getOrDefault(name, false));
            out.add(new FlagView(
                    name, DESC.get(name), enabled(name), envDefault, overrides.containsKey(name),
                    r == null ? null : r.getUpdatedAt(),
                    r == null ? null : r.getUpdatedBy()));
        }
        return out;
    }

    public record FlagView(String name, String description, boolean enabled, boolean envDefault,
                           boolean overridden, Instant updatedAt, String updatedBy) {
    }
}
