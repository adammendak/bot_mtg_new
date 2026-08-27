package com.adam.server.web.dto;

public record AccountView(
        String id,
        String broker,
        String accountName,
        Double equity,
        Double available,
        Double dayPnl,
        String currency,
        boolean connected,
        String error
) {
}
