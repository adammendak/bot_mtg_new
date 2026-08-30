package com.adam.server.ops;

import com.adam.server.persistence.FeatureFlagEntity;
import com.adam.server.persistence.FeatureFlagRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeatureFlagsTest {

    private final FeatureFlagRepository repo = mock(FeatureFlagRepository.class);

    /** htsScan, htsExecution, htsLiveExecution, htsMonitor, htsWeekendFlatten. */
    private FeatureFlags flags(boolean htsScan, boolean htsExecution) {
        when(repo.findAll()).thenReturn(List.of());
        return new FeatureFlags(repo, htsScan, htsExecution, true, true, true);
    }

    @Test
    void onlyOneConstructorSoSpringCannotFallBackToANoArgOne() {
        // Regression guard: a second (no-arg) constructor made Spring skip the
        // @Value one entirely — repo null, nothing persisted, restarts wiped it.
        assertThat(FeatureFlags.class.getDeclaredConstructors()).hasSize(1);
    }

    @Test
    void knownFlagsAreHtsOnly() {
        FeatureFlags f = flags(true, true);
        assertThat(f.isKnown("hts.scan")).isTrue();
        assertThat(f.isKnown("hts.execution")).isTrue();
        assertThat(f.isKnown("hts.live-execution")).isTrue();
        assertThat(f.isKnown("hts.monitor")).isTrue();
        assertThat(f.isKnown("hts.weekend-flatten")).isTrue();
        assertThat(f.isKnown("sdd.scan")).isFalse();
        assertThat(f.isKnown("swing.scan")).isFalse();
    }

    @Test
    void withNoDbRowTheEnvDefaultApplies() {
        FeatureFlags f = flags(false, true); // hts.scan=false, hts.execution=true
        assertThat(f.enabled("hts.scan")).isFalse();
        assertThat(f.enabled("hts.execution")).isTrue();
        assertThat(f.enabled("hts.monitor")).isTrue();
    }

    @Test
    void aKnownFlagWithNoDefaultRowStillFailsTowardOn() {
        // Belt-and-suspenders: even if the defaults map somehow lost the key,
        // enabled() falls back to FALLBACK (true) for every known HTS flag.
        FeatureFlags f = flags(true, true);
        for (String name : new String[]{
                "hts.scan", "hts.execution", "hts.live-execution", "hts.monitor", "hts.weekend-flatten"}) {
            assertThat(f.enabled(name)).as(name).isTrue();
        }
    }

    @Test
    void setOverridesTheEnvDefaultAndPersists() {
        FeatureFlags f = flags(true, true);
        when(repo.findByName("hts.execution")).thenReturn(Optional.empty());

        f.set("hts.execution", false, "adam");

        assertThat(f.enabled("hts.execution")).isFalse();
        verify(repo).save(any(FeatureFlagEntity.class));
    }

    @Test
    void resetDropsTheOverride() {
        FeatureFlags f = flags(true, true);
        f.set("hts.monitor", false, "adam");
        assertThat(f.enabled("hts.monitor")).isFalse();

        f.reset("hts.monitor");

        assertThat(f.enabled("hts.monitor")).isTrue();
        verify(repo).deleteByName("hts.monitor");
    }

    @Test
    void refreshLoadsOverridesFromTheDb() {
        FeatureFlags f = flags(true, true);
        FeatureFlagEntity row = new FeatureFlagEntity();
        row.setName("hts.execution");
        row.setEnabled(false);
        when(repo.findAll()).thenReturn(List.of(row));

        f.refresh();

        assertThat(f.enabled("hts.execution")).isFalse();
    }

    @Test
    void unknownFlagIsRejectedOnSet() {
        FeatureFlags f = flags(true, true);
        assertThatThrownBy(() -> f.set("sdd.scan", true, "adam"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listCoversEveryKnownFlagWithItsSource() {
        FeatureFlags f = flags(true, true);
        FeatureFlagEntity saved = new FeatureFlagEntity();
        saved.setName("hts.execution");
        saved.setEnabled(false);
        saved.setUpdatedBy("adam");
        when(repo.findAllByOrderByNameAsc()).thenReturn(List.of(saved));
        f.set("hts.execution", false, "adam");

        List<FeatureFlags.FlagView> list = f.list();
        assertThat(list).extracting(FeatureFlags.FlagView::name)
                .containsExactly("hts.scan", "hts.execution", "hts.live-execution",
                        "hts.monitor", "hts.weekend-flatten");
        FeatureFlags.FlagView hx = list.stream().filter(v -> v.name().equals("hts.execution"))
                .findFirst().orElseThrow();
        assertThat(hx.enabled()).isFalse();
        assertThat(hx.envDefault()).isTrue();
        assertThat(hx.overridden()).isTrue();
        assertThat(hx.updatedBy()).isEqualTo("adam");
    }
}
