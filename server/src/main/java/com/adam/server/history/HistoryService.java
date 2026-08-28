package com.adam.server.history;

import com.adam.server.config.AppProperties;
import com.adam.server.persistence.BrokerSnapshotEntity;
import com.adam.server.persistence.BrokerSnapshotRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class HistoryService {

    private final BrokerSnapshotRepository brokers;
    private final ZoneId zoneId;

    public HistoryService(BrokerSnapshotRepository brokers, AppProperties properties) {
        this.brokers = brokers;
        this.zoneId = ZoneId.of(properties.getTimezone());
    }

    public HistoryResponse daily(String book) {
        List<BrokerSnapshotEntity> rows = brokers.findByBookOrderByCapturedAtAsc(book);
        Map<LocalDate, BrokerSnapshotEntity> byDay = new LinkedHashMap<>();
        for (BrokerSnapshotEntity row : rows) {
            LocalDate day = row.getCapturedAt().atZone(zoneId).toLocalDate();
            byDay.put(day, row);
        }

        String currency = null;
        boolean connected = false;
        List<DailyEquityPoint> points = new ArrayList<>();
        Double baseEquity = null;
        for (Map.Entry<LocalDate, BrokerSnapshotEntity> entry : byDay.entrySet()) {
            BrokerSnapshotEntity row = entry.getValue();
            if (currency == null) {
                currency = row.getCurrency();
            }
            if (row.isConnected() && row.getEquity() != null) {
                connected = true;
            }
            Double equity = row.getEquity();
            if (equity != null) {
                double e = equity;
                if (baseEquity == null) {
                    baseEquity = e;
                }
            }
            Double pct = null;
            if (equity != null && baseEquity != null && baseEquity != 0.0) {
                pct = ((equity - baseEquity) / baseEquity) * 100.0;
            }
            points.add(new DailyEquityPoint(entry.getKey(), equity, row.getDayPnl(), pct));
        }

        Drawdown dd = Drawdown.of(points);
        return new HistoryResponse(book, currency, connected, points,
                dd.maxDrawdownPct(), dd.currentDrawdownPct(), dd.recoveryDays());
    }

    /** Drawdown metrics computed from the equity series (#15). */
    record Drawdown(Double maxDrawdownPct, Double currentDrawdownPct, Integer recoveryDays) {

        static Drawdown of(List<DailyEquityPoint> points) {
            double peak = Double.NaN;
            double maxDd = 0;
            double currentDd = 0;
            int ddStart = -1;
            int maxDdStart = -1;
            int maxDdEnd = -1;
            for (int i = 0; i < points.size(); i++) {
                Double eq = points.get(i).equity();
                if (eq == null || !Double.isFinite(eq)) {
                    continue;
                }
                if (Double.isNaN(peak) || eq > peak) {
                    peak = eq;
                    ddStart = -1; // new peak, not in drawdown
                } else if (peak > 0) {
                    double dd = (peak - eq) / peak * 100.0;
                    if (ddStart < 0) {
                        ddStart = i;
                    }
                    if (dd > maxDd) {
                        maxDd = dd;
                        maxDdStart = ddStart;
                        maxDdEnd = i;
                    }
                    currentDd = dd;
                }
            }
            if (points.isEmpty()) {
                return new Drawdown(0.0, 0.0, null);
            }
            Double maxPct = maxDd <= 0 ? null : round2(maxDd);
            Double curPct = round2(currentDd);
            Integer recovery = null;
            if (maxDdStart >= 0) {
                // Days from the drawdown trough until equity regains the pre-drawdown peak.
                Double peakBefore = points.get(maxDdStart - 1 >= 0 ? maxDdStart - 1 : maxDdStart).equity();
                if (peakBefore != null && maxDdEnd >= 0) {
                    for (int i = maxDdEnd + 1; i < points.size(); i++) {
                        Double eq = points.get(i).equity();
                        if (eq != null && eq >= peakBefore) {
                            recovery = i - maxDdStart;
                            break;
                        }
                    }
                }
            }
            return new Drawdown(maxPct, curPct, recovery);
        }

        private static double round2(double v) {
            return Math.round(v * 100.0) / 100.0;
        }
    }
}
