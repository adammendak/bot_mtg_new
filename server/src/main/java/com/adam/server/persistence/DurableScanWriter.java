package com.adam.server.persistence;

import com.adam.server.scan.ScanSnapshot;
import com.adam.server.sdd.SddScan;
import com.adam.server.web.dto.AccountView;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class DurableScanWriter {

    private static final Logger log = LoggerFactory.getLogger(DurableScanWriter.class);
    private static final int MAX_SIGNALS = 200;

    private final SddScanRepository scans;
    private final SddSignalRepository signals;
    private final BrokerSnapshotRepository brokers;
    private final ObjectMapper mapper;

    public DurableScanWriter(
            SddScanRepository scans,
            SddSignalRepository signals,
            BrokerSnapshotRepository brokers,
            ObjectMapper mapper
    ) {
        this.scans = scans;
        this.signals = signals;
        this.brokers = brokers;
        this.mapper = mapper;
    }

    @Transactional
    public void write(ScanSnapshot snapshot, AccountView demo, AccountView live) {
        try {
            SddScanEntity scanRow = new SddScanEntity();
            Instant at = snapshot.scannedAt() == null ? Instant.now() : snapshot.scannedAt();
            scanRow.setScannedAt(at);
            scanRow.setBrokerId(snapshot.brokerId());
            scanRow.setBrokerName(snapshot.brokerName());
            scanRow.setExecutionEnabled(snapshot.executionEnabled());
            scanRow.setNewsBlackout(snapshot.newsBlackout());
            scanRow.setError(snapshot.error());
            scanRow.setPayload(mapper.writeValueAsString(snapshot));
            scanRow.setCreatedAt(Instant.now());
            SddScanEntity saved = scans.save(scanRow);

            for (SddScan symbol : snapshot.symbols()) {
                if (!symbol.fullStack() && !symbol.flip()) {
                    continue;
                }
                SddSignalEntity row = new SddSignalEntity();
                row.setScanId(saved.getId());
                row.setScannedAt(symbol.timestamp() == null ? at : symbol.timestamp());
                row.setSymbol(symbol.symbol());
                row.setEpic(symbol.epic());
                row.setDirection(symbol.direction() == null ? null : symbol.direction().name());
                row.setFullStack(symbol.fullStack());
                row.setFlip(symbol.flip());
                row.setReason(symbol.reason());
                row.setPayload(mapper.writeValueAsString(symbol));
                row.setCreatedAt(Instant.now());
                signals.save(row);
            }
            persistBook(demo, at);
            persistBook(live, at);
        } catch (Exception e) {
            log.warn("Durable scan write failed");
        }
    }

    public ScanSnapshot loadLast() {
        return scans.findTopByOrderByIdDesc()
                .map(row -> {
                    try {
                        return mapper.readValue(row.getPayload(), ScanSnapshot.class);
                    } catch (Exception e) {
                        log.warn("Could not reload last scan payload");
                        return null;
                    }
                })
                .orElse(null);
    }

    public List<SddScan> loadSignals() {
        List<SddScan> out = new ArrayList<>();
        for (SddSignalEntity row : signals.findAllByOrderByIdDesc(PageRequest.of(0, MAX_SIGNALS))) {
            try {
                out.add(mapper.readValue(row.getPayload(), SddScan.class));
            } catch (Exception e) {
                log.warn("Could not reload a stored signal");
            }
        }
        return out;
    }

    private void persistBook(AccountView view, Instant at) {
        if (view == null) {
            return;
        }
        try {
            BrokerSnapshotEntity row = new BrokerSnapshotEntity();
            row.setBook(view.id());
            row.setBroker(view.broker());
            row.setAccountName(view.accountName());
            row.setEquity(view.equity());
            row.setAvailable(view.available());
            row.setDayPnl(view.dayPnl());
            row.setCurrency(view.currency());
            row.setConnected(view.connected());
            row.setError(view.error());
            row.setCapturedAt(at);
            row.setPayload(mapper.writeValueAsString(view));
            brokers.save(row);
        } catch (Exception e) {
            log.warn("Durable broker snapshot write failed");
        }
    }
}
