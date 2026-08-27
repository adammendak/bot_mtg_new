package com.adam.server.scan;

import com.adam.server.sdd.SddScan;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class ScanStore {

    private static final int MAX_SIGNALS = 200;

    private volatile ScanSnapshot last = ScanSnapshot.empty(null, "unknown", "unknown", false);
    private final CopyOnWriteArrayList<SddScan> signals = new CopyOnWriteArrayList<>();

    public ScanSnapshot last() {
        return last;
    }

    public void save(ScanSnapshot snapshot) {
        this.last = snapshot;
        for (SddScan scan : snapshot.symbols()) {
            if (scan.fullStack() || scan.flip()) {
                signals.add(0, scan);
            }
        }
        trim();
    }

    public List<SddScan> signals() {
        return List.copyOf(signals);
    }

    public void addSignal(SddScan scan) {
        signals.add(0, scan);
        trim();
    }

    private void trim() {
        while (signals.size() > MAX_SIGNALS) {
            signals.remove(signals.size() - 1);
        }
    }
}
