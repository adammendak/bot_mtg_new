package com.adam.server.ops;

import com.adam.server.persistence.FeatureFlagEntity;
import com.adam.server.persistence.FeatureFlagRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
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
 * Runtime feature flags (E-6). Each flag overrides one env-var boolean —
 * execution / scan / monitor toggles — without a dyno restart (and without the
 * Capital.com 429 storm a restart triggers). A DB row wins; with no row the
 * {@code application.properties} default applies. The override cache is refreshed
 * on every write and re-read from the DB every 30 s as a safety net.
 *
 * <p>The set of known flags is fixed here so the admin UI and the callers agree.
 */
@Service
public class FeatureFlags {

    private static final Logger log = LoggerFactory.getLogger(FeatureFlags.class);

    /** flag name → the env property that is its default, in display order. */
    private static final Map<String, String> KEY = new LinkedHashMap<>();
    /** flag name → human description for the admin panel. */
    private static final Map<String, String> DESC = new LinkedHashMap<>();
    /** flag name → default when the property is unset. */
    private static final Map<String, Boolean> FALLBACK = new LinkedHashMap<>();

    static {
        def("sdd.scan", "app.scan.enabled", true, "Skan SDD-M15 (zarchiwizowany)");
        def("sdd.execution", "app.execution-enabled", false, "Egzekucja SDD-M15 na demo/live");
        def("swing.scan", "app.swing.enabled", true, "Skan SDD-SWING H1 (zarchiwizowany)");
        def("swing.execution", "app.swing.execution-enabled", false, "Egzekucja SDD-SWING");
        def("hts.scan", "app.hts.scan-enabled", true, "Skan HTS (wszystkie warianty)");
        def("hts.execution", "app.hts.execution-enabled", false, "Egzekucja HTS na kontach demo");
        def("hts.live-execution", "app.hts.live-execution-enabled", false, "Egzekucja HTS CORE_LIVE (realne)");
        def("hts.monitor", "app.hts.monitor-enabled", true, "Monitor pozycji HTS (runner exit)");
    }

    private static void def(String name, String prop, boolean fallback, String desc) {
        KEY.put(name, prop);
        FALLBACK.put(name, fallback);
        DESC.put(name, desc);
    }

    private final FeatureFlagRepository repo;
    private final Map<String, Boolean> defaults = new LinkedHashMap<>();
    private final Map<String, Boolean> overrides = new ConcurrentHashMap<>();

    public FeatureFlags(FeatureFlagRepository repo, Environment env) {
        this.repo = repo;
        for (Map.Entry<String, String> e : KEY.entrySet()) {
            defaults.put(e.getKey(), env.getProperty(e.getValue(), Boolean.class, FALLBACK.get(e.getKey())));
        }
        refresh();
    }

    private FeatureFlags() {
        this.repo = null;
        defaults.putAll(FALLBACK);
    }

    /** Test instance: property fallbacks as defaults, no DB. {@link #set}/{@link #reset} just move the cache. */
    public static FeatureFlags forTest() {
        return new FeatureFlags();
    }

    /** Effective value: DB override if set, otherwise the env default. */
    public boolean enabled(String name) {
        Boolean o = overrides.get(name);
        if (o != null) {
            return o;
        }
        return defaults.getOrDefault(name, false);
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
            boolean envDefault = defaults.getOrDefault(name, false);
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
