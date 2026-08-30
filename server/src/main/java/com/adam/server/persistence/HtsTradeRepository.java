package com.adam.server.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface HtsTradeRepository extends JpaRepository<HtsTradeEntity, Long> {

    List<HtsTradeEntity> findByStatusOrderByIdDesc(String status);

    List<HtsTradeEntity> findAllByOrderByIdDesc(Pageable pageable);

    /** Idempotency: has this exact signal bar already been executed for this variant? */
    boolean existsByVariantAndSymbolAndDirectionAndBarTime(
            String variant, String symbol, String direction, Instant barTime);

    /** Today's realised P/L on a book — for the live day-halt. */
    List<HtsTradeEntity> findByBookAndStatusAndExitAtAfter(String book, String status, Instant since);
}
