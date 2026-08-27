package com.adam.server.broker.capital;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Capital.com REST payloads. Kept in this package so strategy/UI never see them.
 */
final class CapitalJson {

    private CapitalJson() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SessionRequest(String identifier, String password, boolean encryptedPassword) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AccountsResponse(List<AccountJson> accounts) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AccountJson(
            String accountId,
            String accountName,
            String currency,
            boolean preferred,
            BalanceJson balance
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BalanceJson(Double balance, Double deposit, Double profitLoss, Double available) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PricesResponse(List<PriceJson> prices) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PriceJson(
            String snapshotTime,
            String snapshotTimeUTC,
            BidAsk openPrice,
            BidAsk highPrice,
            BidAsk lowPrice,
            BidAsk closePrice,
            Double lastTradedVolume
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BidAsk(Double bid, Double ask) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MarketResponse(SnapshotJson snapshot, InstrumentJson instrument) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record InstrumentJson(String epic) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SnapshotJson(Double bid, Double offer, Double ask, String updateTimeUTC) {
        Double askOrOffer() {
            return ask != null ? ask : offer;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PositionsResponse(List<PositionWrapper> positions) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PositionWrapper(PositionBody position, MarketBody market) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PositionBody(
            String dealId,
            String dealReference,
            Double size,
            String direction,
            Double level,
            Double stopLevel,
            Double profitLevel,
            Double upl,
            String currency,
            String createdDateUTC
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MarketBody(String epic) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DealReferenceResponse(String dealReference) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ConfirmResponse(
            String date,
            String status,
            String dealStatus,
            String epic,
            String dealReference,
            String dealId,
            String direction,
            Double level,
            Double size
    ) {
    }
}
