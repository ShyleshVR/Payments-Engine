package com.shylesh.payout_service.payout;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PayoutRepository extends JpaRepository<Payout, UUID> {

    Optional<Payout> findByIdAndMerchantId(UUID id, UUID merchantId);

    Optional<Payout> findByMerchantIdAndIdempotencyKey(UUID merchantId, String idempotencyKey);

    List<Payout> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId, Limit limit);

    List<Payout> findAllByOrderByCreatedAtDesc(Limit limit);

    List<Payout> findByIdIn(Collection<UUID> ids);

    @Query("""
            SELECT COUNT(p) > 0 FROM Payout p
            WHERE p.merchantId = :merchantId AND p.currency = :currency
              AND p.trigger = com.shylesh.payout_service.payout.PayoutTrigger.BATCH AND p.batchDate = :batchDate
            """)
    boolean existsBatchPayout(@Param("merchantId") UUID merchantId, @Param("currency") String currency,
                              @Param("batchDate") LocalDate batchDate);
}
