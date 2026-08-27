package com.adam.server.sdd;

import com.adam.server.broker.Direction;

import java.time.Instant;
import java.util.List;

public record SddScan(
        Instant timestamp,
        String symbol,
        String epic,
        Direction direction,
        Setup setup,
        double stop,
        double oneR,
        double atrH1,
        double entry,
        boolean actionable,
        String reason,
        List<String> failed,
        boolean newBar,
        boolean flip,
        boolean fullStack,
        String h4Note,
        boolean h1Supporting
) {
    public record Setup(boolean ha, boolean rma, boolean h1, boolean pp) {
    }
}
