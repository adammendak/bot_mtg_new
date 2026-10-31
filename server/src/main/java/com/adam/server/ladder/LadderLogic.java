package com.adam.server.ladder;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Pure decision rule of the long ladder (docs/DAILY-SCALP-LONGONLY.md): long only, NO stop-loss, every position
 * waits for its own take-profit.
 * <ul>
 *   <li>flat: buy once per weekday shortly after the session open ({@code openUtc});</li>
 *   <li>otherwise: buy another position when the bid falls {@code stepPct} below the LOWEST open entry;</li>
 *   <li>never above {@code maxPositions} open positions;</li>
 *   <li>account failsafe: no new entries once equity is {@code failsafeDropPct} below the start equity.</li>
 * </ul>
 */
public final class LadderLogic {

    private LadderLogic() {
    }

    public record Params(String name, String epic, double tpPct, double stepPct, int maxPositions, double gainPct,
                         LocalTime openUtc, int openWindowMin) {
        /** Notional of one entry as a multiple of account equity: a TP of {@code tpPct} must earn {@code gainPct}. */
        public double notionalMultiple() {
            return gainPct / tpPct;
        }
    }

    public enum Action { NONE, BUY }

    public record Decision(Action action, String reason) {
        static Decision none(String why) {
            return new Decision(Action.NONE, why);
        }

        static Decision buy(String why) {
            return new Decision(Action.BUY, why);
        }

        public boolean buy() {
            return action == Action.BUY;
        }
    }

    /**
     * @param openEntries   entry prices of the currently open ladder positions
     * @param lastOpenDay   day of the last daily-open entry (null = never)
     * @param startEquity   equity the failsafe is measured from (0 = failsafe off)
     * @param trendOk       variant B trend filter: false blocks every new entry (variant A always passes true)
     */
    public static Decision decide(Params p, List<Double> openEntries, double bid, Instant now, LocalDate lastOpenDay,
                                  double equity, double startEquity, double failsafeDropPct, boolean trendOk) {
        if (bid <= 0) {
            return Decision.none("no price");
        }
        if (openEntries.size() >= p.maxPositions()) {
            return Decision.none("position cap " + p.maxPositions() + " reached");
        }
        if (startEquity > 0 && equity <= startEquity * (1.0 - failsafeDropPct / 100.0)) {
            return Decision.none("FAILSAFE: equity " + Math.round(equity) + " <= " + (100.0 - failsafeDropPct)
                    + "% of start " + Math.round(startEquity));
        }
        if (!trendOk) {
            return Decision.none("TREND filter: the previous daily close is below its moving average");
        }
        if (openEntries.isEmpty()) {
            var utc = now.atZone(ZoneOffset.UTC);
            int dow = utc.getDayOfWeek().getValue();
            if (dow >= 6) {
                return Decision.none("weekend");
            }
            LocalTime t = utc.toLocalTime();
            boolean inWindow = !t.isBefore(p.openUtc()) && t.isBefore(p.openUtc().plusMinutes(p.openWindowMin()));
            if (!inWindow) {
                return Decision.none("flat, waiting for the " + p.openUtc() + " UTC open");
            }
            if (utc.toLocalDate().equals(lastOpenDay)) {
                return Decision.none("daily-open entry already taken today");
            }
            return Decision.buy("DAILY_OPEN");
        }
        double lowest = openEntries.stream().mapToDouble(Double::doubleValue).min().orElse(Double.MAX_VALUE);
        double trigger = lowest * (1.0 - p.stepPct() / 100.0);
        if (bid <= trigger) {
            return Decision.buy("LADDER bid " + bid + " <= " + trigger + " (lowest entry " + lowest + ")");
        }
        return Decision.none("above the next ladder level " + trigger);
    }
}
