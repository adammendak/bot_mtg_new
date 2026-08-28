package com.adam.server.scan;

import com.adam.server.broker.Direction;
import com.adam.server.broker.model.Position;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Correlated-exposure calculator for the risk panel.
 *
 * <p>SDD-M15 trades correlated markets (e.g. US100 and GER40 move together).
 * When two open positions sit in the same correlation group and the same
 * direction, their worst-case losses are <em>concentrated</em>: if the pair
 * moves against them, both stops are hit together, so the effective risk is
 * the full sum. When they sit on opposite sides of the same group they
 * <em>hedge</em> each other, so the effective risk is the net remainder.
 *
 * <p>Both views are surfaced in the overview:
 * <ul>
 *   <li>{@code correlatedPln} — sum of worst-case loss of positions that share
 *       a correlation group with another open position (concentration).</li>
 *   <li>{@code effectiveRiskPln} — per group, |Σ signed worst-case| where BUY
 *       is + and SELL is −, summed across groups (net of intra-group hedges;
 *       cross-group risk still adds up).</li>
 * </ul>
 */
public final class RiskExposure {

    /** Epics that move together; groups are matched on normalized epic name. */
    private static final Map<String, String> GROUP_BY_EPIC = Map.ofEntries(
            Map.entry("US100", "equity-index"),
            Map.entry("GER40", "equity-index"),
            Map.entry("DE40", "equity-index"),
            Map.entry("NAS100", "equity-index"),
            Map.entry("SPX500", "equity-index"),
            Map.entry("XAU", "metal"),
            Map.entry("GOLD", "metal"),
            Map.entry("BTCUSD", "crypto"),
            Map.entry("ETHUSD", "crypto")
    );

    private RiskExposure() {
    }

    /** Worst-case loss (PLN, signed positive) if this position's stop is hit, 0 when no stop. */
    static double worstCasePln(Position p) {
        if (p.stopLevel() == null) {
            return 0;
        }
        double distance = Direction.BUY == p.direction()
                ? p.level() - p.stopLevel()
                : p.stopLevel() - p.level();
        return distance > 0 ? distance * p.size() : 0;
    }

    /** Correlation group key for an epic, or {@code null} when the market is not correlated to another. */
    static String groupOf(String epic) {
        if (epic == null) {
            return null;
        }
        return GROUP_BY_EPIC.get(epic.toUpperCase(Locale.ROOT).trim());
    }

    /**
     * @return [correlatedPln, effectiveRiskPln]
     */
    public static double[] compute(List<Position> positions) {
        // group -> signed risks (BUY +, SELL -)
        Map<String, List<Double>> byGroup = new LinkedHashMap<>();
        double correlated = 0.0;
        double effective = 0.0;
        double ungrouped = 0.0;

        if (positions != null) {
            for (Position p : positions) {
                double worst = worstCasePln(p);
                if (worst <= 0) {
                    continue;
                }
                String group = groupOf(p.epic());
                if (group == null) {
                    ungrouped += worst;
                    continue;
                }
                byGroup.computeIfAbsent(group, k -> new ArrayList<>())
                        .add(Direction.BUY == p.direction() ? worst : -worst);
            }
        }

        for (List<Double> signed : byGroup.values()) {
            if (signed.size() > 1) {
                // Same correlation group, multiple legs -> concentrated exposure.
                correlated += signed.stream().mapToDouble(Math::abs).sum();
            }
            // Net within the group: same-side legs add, opposite-side legs hedge.
            effective += Math.abs(signed.stream().mapToDouble(Double::doubleValue).sum());
        }
        effective += ungrouped;

        return new double[]{correlated, effective};
    }
}
