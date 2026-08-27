package com.adam.server.broker.model;

import com.adam.server.broker.Direction;

public record Confirmation(
        String dealReference,
        String dealId,
        String status,
        String dealStatus,
        String epic,
        Direction direction,
        Double level,
        Double size
) {
    public boolean accepted() {
        return "ACCEPTED".equalsIgnoreCase(dealStatus) || "OPEN".equalsIgnoreCase(status);
    }
}
