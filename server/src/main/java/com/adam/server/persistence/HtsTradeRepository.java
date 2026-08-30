package com.adam.server.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface HtsTradeRepository extends JpaRepository<HtsTradeEntity, Long> {

    List<HtsTradeEntity> findByStatusOrderByIdDesc(String status);

    List<HtsTradeEntity> findAllByOrderByIdDesc(Pageable pageable);

    /** Whole history in entry order — the forward-test scorecard (E-4). */
    List<HtsTradeEntity> findAllByOrderByIdAsc();

    /** Idempotency: has this exact signal bar already been executed for this variant? */
    boolean existsByVariantAndSymbolAndDirectionAndBarTime(
            String variant, String symbol, String direction, Instant barTime);

    /** No stacking: does this variant already hold a position for the symbol? */
    boolean existsByVariantAndSymbolAndStatus(String variant, String symbol, String status);

    /** Today's realised P/L on a book — for the live day-halt. */
    List<HtsTradeEntity> findByBookAndStatusAndExitAtAfter(String book, String status, Instant since);
}
