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

    /** sddScan, sddExec, swingScan, swingExec, htsScan, htsExec, htsLiveExec, htsMonitor. */
    private FeatureFlags flags(boolean sddScan, boolean htsExec) {
        when(repo.findAll()).thenReturn(List.of());
        return new FeatureFlags(repo, sddScan, false, true, false, true, htsExec, false, true);
    }

    @Test
    void withNoDbRowTheEnvDefaultApplies() {
        FeatureFlags f = flags(false, true); // sdd.scan=false, hts.execution=true
        assertThat(f.enabled("hts.execution")).isTrue();
        assertThat(f.enabled("sdd.scan")).isFalse();
        assertThat(f.enabled("hts.monitor")).isTrue();
        assertThat(f.enabled("sdd.execution")).isFalse();
    }

    @Test
    void setOverridesTheEnvDefaultAndPersists() {
        FeatureFlags f = flags(true, false); // hts.execution env default = false
        when(repo.findByName("hts.execution")).thenReturn(Optional.empty());

        f.set("hts.execution", true, "adam");

        assertThat(f.enabled("hts.execution")).isTrue();
        verify(repo).save(any(FeatureFlagEntity.class));
    }

    @Test
    void resetDropsTheOverride() {
        FeatureFlags f = flags(true, false);
        f.set("hts.monitor", false, "adam");
        assertThat(f.enabled("hts.monitor")).isFalse();

        f.reset("hts.monitor");

        assertThat(f.enabled("hts.monitor")).isTrue();
        verify(repo).deleteByName("hts.monitor");
    }

    @Test
    void refreshLoadsOverridesFromTheDb() {
        FeatureFlags f = flags(true, false);
        FeatureFlagEntity row = new FeatureFlagEntity();
        row.setName("hts.execution");
        row.setEnabled(true);
        when(repo.findAll()).thenReturn(List.of(row));

        f.refresh();

        assertThat(f.enabled("hts.execution")).isTrue();
    }

    @Test
    void unknownFlagIsRejected() {
        FeatureFlags f = flags(true, false);
        assertThatThrownBy(() -> f.set("bogus.flag", true, "adam"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(f.isKnown("hts.execution")).isTrue();
        assertThat(f.isKnown("bogus.flag")).isFalse();
    }

    @Test
    void listCoversEveryKnownFlagWithItsSource() {
        FeatureFlags f = flags(true, false);
        FeatureFlagEntity saved = new FeatureFlagEntity();
        saved.setName("hts.execution");
        saved.setEnabled(true);
        saved.setUpdatedBy("adam");
        when(repo.findAllByOrderByNameAsc()).thenReturn(List.of(saved));
        f.set("hts.execution", true, "adam");

        List<FeatureFlags.FlagView> list = f.list();
        assertThat(list).extracting(FeatureFlags.FlagView::name)
                .contains("sdd.scan", "sdd.execution", "swing.scan", "swing.execution",
                        "hts.scan", "hts.execution", "hts.live-execution", "hts.monitor");
        FeatureFlags.FlagView hx = list.stream().filter(v -> v.name().equals("hts.execution"))
                .findFirst().orElseThrow();
        assertThat(hx.enabled()).isTrue();
        assertThat(hx.envDefault()).isFalse();
        assertThat(hx.overridden()).isTrue();
        assertThat(hx.updatedBy()).isEqualTo("adam");
    }
}
