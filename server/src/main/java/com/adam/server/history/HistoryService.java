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

        return new HistoryResponse(book, currency, connected, points);
    }
}
