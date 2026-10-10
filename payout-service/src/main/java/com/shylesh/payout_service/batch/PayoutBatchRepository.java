package com.shylesh.payout_service.batch;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PayoutBatchRepository extends JpaRepository<PayoutBatch, LocalDate> {

    List<PayoutBatch> findAllByOrderByBatchDateDesc(Limit limit);

    Optional<PayoutBatch> findFirstByStatusOrderByFinishedAtDesc(PayoutBatch.Status status);
}
