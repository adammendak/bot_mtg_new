package com.adam.server.broker.model;

public record OrderAck(String dealReference, String dealId, String status) {
}
