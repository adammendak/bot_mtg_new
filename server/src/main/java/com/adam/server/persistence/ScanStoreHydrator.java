package com.adam.server.persistence;

import com.adam.server.scan.ScanSnapshot;
import com.adam.server.scan.ScanStore;
import com.adam.server.sdd.SddScan;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.List;

@Component
public class ScanStoreHydrator {

    private final ScanStore store;
    private final DurableScanWriter durable;

    public ScanStoreHydrator(ScanStore store, DurableScanWriter durable) {
        this.store = store;
        this.durable = durable;
    }

    @PostConstruct
    void hydrate() {
        ScanSnapshot last = durable.loadLast();
        List<SddScan> signals = durable.loadSignals();
        store.seed(last, signals);
    }
}
