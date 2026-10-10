package com.shylesh.payout_service.web;

import com.shylesh.payout_service.batch.PayoutBatch;
import com.shylesh.payout_service.payout.Payout;
import com.shylesh.payout_service.payout.PayoutDestination;
import com.shylesh.payout_service.saga.PayoutSaga;
import com.shylesh.payout_service.saga.PayoutSagaStep;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Request and response bodies of the payout API. */
public final class PayoutViews {

    private PayoutViews() {
    }

    public record PayoutView(String payoutId, String status, BigDecimal amount, String currency, String bankAccount,
                             String trigger, String failureCode, String transferId, LocalDate batchDate,
                             LocalDateTime createdAt, LocalDateTime paidAt, LocalDateTime failedAt, LocalDateTime returnedAt) {

        static PayoutView of(Payout p) {
            return new PayoutView(p.publicId(), p.getStatus().name(), p.getAmount(), p.getCurrency(), p.getBankAccount(),
                    p.getTrigger().name(), p.getFailureCode(), p.getTransferId(), p.getBatchDate(),
                    p.getCreatedAt(), p.getPaidAt(), p.getFailedAt(), p.getReturnedAt());
        }
    }

    /** @param bankAccount a bank account token (test accounts: ba_test_ok, ba_test_closed, ...) */
    public record DestinationRequest(
            @NotBlank @Pattern(regexp = "ba_[a-z0-9_]{1,60}", message = "must be a bank account token such as ba_test_ok")
            String bankAccount) {
    }

    public record DestinationView(String bankAccount, LocalDateTime updatedAt) {

        static DestinationView of(PayoutDestination d) {
            return new DestinationView(d.getBankAccount(), d.getUpdatedAt());
        }
    }

    public record InstantPayoutRequest(
            @NotNull @DecimalMin("0.01") @Digits(integer = 15, fraction = 4) BigDecimal amount,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency) {
    }

    /** @param cutoff money settled after this is not payable yet */
    public record BalanceView(String currency, BigDecimal payable, LocalDateTime cutoff) {
    }

    public record BatchView(LocalDate batchDate, String status, LocalDateTime cutoff, Integer payoutsCreated,
                            Integer skippedNoDestination, String error, LocalDateTime startedAt, LocalDateTime finishedAt) {

        static BatchView of(PayoutBatch b) {
            return new BatchView(b.getBatchDate(), b.getStatus().name(), b.getCutoff(), b.getPayoutsCreated(),
                    b.getSkippedNoDestination(), b.getError(), b.getStartedAt(), b.getFinishedAt());
        }
    }

    public record StepView(String state, String outcome, String detail, LocalDateTime occurredAt) {
    }

    public record SagaView(UUID sagaId, String payoutId, String state, String stuckState, String lastError, int attempt,
                           String failureCode, LocalDateTime createdAt, LocalDateTime finishedAt, List<StepView> steps) {

        static SagaView of(PayoutSaga s, List<PayoutSagaStep> steps) {
            return new SagaView(s.getId(), Payout.PUBLIC_ID_PREFIX + s.getPayoutId(), s.getState().name(),
                    s.getStuckState() == null ? null : s.getStuckState().name(), s.getLastError(), s.getAttempt(),
                    s.getFailureCode(), s.getCreatedAt(), s.getFinishedAt(),
                    steps.stream().map(step -> new StepView(step.getState().name(), step.getOutcome(), step.getDetail(),
                            step.getOccurredAt())).toList());
        }
    }

    public record LookupRequest(@NotEmpty @Size(max = 1000) List<String> payoutIds) {
    }

    /**
     * A payout as the reconciliation sees it.
     *
     * @param inFlight true while its money movements may still be incomplete (saga running, not in
     *                 the return window, where everything is booked)
     */
    public record AuditView(String payoutId, UUID merchantId, String status, BigDecimal amount, String currency,
                            String failureCode, String transferId, boolean inFlight, LocalDateTime createdAt,
                            LocalDateTime paidAt, LocalDateTime failedAt, LocalDateTime returnedAt) {
    }
}
