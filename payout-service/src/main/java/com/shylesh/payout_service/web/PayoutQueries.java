package com.shylesh.payout_service.web;

import com.shylesh.payout_service.batch.PayoutBatchRepository;
import com.shylesh.payout_service.payout.Payout;
import com.shylesh.payout_service.payout.PayoutNotFoundException;
import com.shylesh.payout_service.payout.PayoutRepository;
import com.shylesh.payout_service.saga.PayoutSaga;
import com.shylesh.payout_service.saga.PayoutSagaRepository;
import com.shylesh.payout_service.saga.PayoutSagaStepRepository;
import com.shylesh.payout_service.web.PayoutViews.AuditView;
import com.shylesh.payout_service.web.PayoutViews.BatchView;
import com.shylesh.payout_service.web.PayoutViews.PayoutView;
import com.shylesh.payout_service.web.PayoutViews.SagaView;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Read side of the payout API. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PayoutQueries {

    private final PayoutRepository payoutRepository;
    private final PayoutSagaRepository sagaRepository;
    private final PayoutSagaStepRepository stepRepository;
    private final PayoutBatchRepository batchRepository;

    /** "po_<uuid>": anything else is a malformed id (400). */
    public static UUID parseId(String publicId) {
        if (publicId == null || !publicId.startsWith(Payout.PUBLIC_ID_PREFIX)) {
            throw new IllegalArgumentException("Payout ids look like po_<uuid>");
        }
        try {
            return UUID.fromString(publicId.substring(Payout.PUBLIC_ID_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Payout ids look like po_<uuid>");
        }
    }

    public PayoutView own(UUID merchantId, UUID payoutId) {
        return PayoutView.of(payoutRepository.findByIdAndMerchantId(payoutId, merchantId)
                .orElseThrow(() -> new PayoutNotFoundException(payoutId)));
    }

    public PayoutView any(UUID payoutId) {
        return PayoutView.of(payoutRepository.findById(payoutId).orElseThrow(() -> new PayoutNotFoundException(payoutId)));
    }

    public List<PayoutView> own(UUID merchantId, int limit) {
        return payoutRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId, Limit.of(limit)).stream().map(PayoutView::of).toList();
    }

    public List<PayoutView> all(int limit) {
        return payoutRepository.findAllByOrderByCreatedAtDesc(Limit.of(limit)).stream().map(PayoutView::of).toList();
    }

    public SagaView saga(UUID payoutId) {
        PayoutSaga saga = sagaRepository.findByPayoutId(payoutId).orElseThrow(() -> new PayoutNotFoundException(payoutId));
        return SagaView.of(saga, stepRepository.findBySagaIdOrderByIdAsc(saga.getId()));
    }

    public List<BatchView> batches(int limit) {
        return batchRepository.findAllByOrderByBatchDateDesc(Limit.of(limit)).stream().map(BatchView::of).toList();
    }

    /** Payouts by id, for the reconciliation; unknown ids are left out. */
    public List<AuditView> lookup(List<UUID> payoutIds) {
        Set<UUID> inFlight = new HashSet<>(sagaRepository.findPayoutIdsInFlight(payoutIds));
        return payoutRepository.findByIdIn(payoutIds).stream()
                .map(p -> new AuditView(p.publicId(), p.getMerchantId(), p.getStatus().name(), p.getAmount(), p.getCurrency(),
                        p.getFailureCode(), p.getTransferId(), inFlight.contains(p.getId()), p.getCreatedAt(),
                        p.getPaidAt(), p.getFailedAt(), p.getReturnedAt()))
                .toList();
    }
}
