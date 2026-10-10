package com.shylesh.payout_service.payout;

import com.shylesh.payout_service.client.LedgerClient;
import com.shylesh.payout_service.config.PayoutProperties;
import com.shylesh.payout_service.saga.PayoutOrchestrator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Creating payouts (the daily batch and merchants' instant payouts) and the merchant's payout
 * destination. A payout and the start of its saga commit together; the ledger hold that follows
 * is what actually reserves the money, so the payable-balance check here is only a courtesy to
 * give the merchant an immediate answer.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayoutService {

    public static final String NO_DESTINATION = "no_payout_destination";
    public static final String INSUFFICIENT_PAYABLE_BALANCE = "insufficient_payable_balance";
    public static final String KEY_REUSED = "idempotency_key_reused";

    private final PayoutRepository payoutRepository;
    private final PayoutDestinationRepository destinationRepository;
    private final PayoutOrchestrator orchestrator;
    private final LedgerClient ledgerClient;
    private final PayoutProperties properties;
    private final TransactionTemplate transactionTemplate;

    /** @param replayed true when an earlier request with the same Idempotency-Key created it */
    public record InstantResult(Payout payout, boolean replayed) {
    }

    @Transactional
    public PayoutDestination setDestination(UUID merchantId, String bankAccount) {
        LocalDateTime now = LocalDateTime.now();
        PayoutDestination destination = destinationRepository.findById(merchantId)
                .orElseGet(() -> PayoutDestination.builder().merchantId(merchantId).createdAt(now).build());
        destination.change(bankAccount, now);
        return destinationRepository.save(destination);
    }

    @Transactional(readOnly = true)
    public Optional<PayoutDestination> destination(UUID merchantId) {
        return destinationRepository.findById(merchantId);
    }

    /** Only money settled before this is paid out now. */
    public LocalDateTime cutoff(LocalDateTime now) {
        return now.minus(properties.delay());
    }

    public BigDecimal payable(UUID merchantId, String currency) {
        return ledgerClient.payable(merchantId, currency, cutoff(LocalDateTime.now()));
    }

    /**
     * A payout the merchant asked for. The same Idempotency-Key with the same amount and currency
     * returns the payout it created; with anything else, 422. The ledger call runs before any
     * transaction is opened.
     */
    public InstantResult requestInstant(UUID merchantId, String idempotencyKey, BigDecimal amount, String currency) {
        String requestHash = hash(amount.stripTrailingZeros().toPlainString(), currency);
        Optional<InstantResult> replay = replay(merchantId, idempotencyKey, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        PayoutDestination destination = destination(merchantId).orElseThrow(() -> new PayoutRequestException(NO_DESTINATION,
                "Set a payout destination first (PUT /api/v1/payouts/destination)"));
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoff = cutoff(now);
        BigDecimal payable = ledgerClient.payable(merchantId, currency, cutoff);
        if (payable.compareTo(amount) < 0) {
            throw new PayoutRequestException(INSUFFICIENT_PAYABLE_BALANCE,
                    "The payable balance is " + payable.toPlainString() + " " + currency
                            + " (money settled in the last " + properties.delay() + " is not payable yet)");
        }
        Payout payout = Payout.builder()
                .id(UUID.randomUUID())
                .merchantId(merchantId)
                .amount(amount)
                .currency(currency)
                .bankAccount(destination.getBankAccount())
                .status(PayoutStatus.PENDING)
                .trigger(PayoutTrigger.INSTANT)
                .idempotencyKey(idempotencyKey)
                .requestHash(requestHash)
                .cutoff(cutoff)
                .createdAt(now)
                .updatedAt(now)
                .build();
        try {
            return new InstantResult(createAndStart(payout), false);
        } catch (DataIntegrityViolationException e) {
            // the same key, at the same moment, from a concurrent retry: that one won
            return replay(merchantId, idempotencyKey, requestHash).orElseThrow(() -> e);
        }
    }

    private Optional<InstantResult> replay(UUID merchantId, String idempotencyKey, String requestHash) {
        return payoutRepository.findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey).map(existing -> {
            if (!requestHash.equals(existing.getRequestHash())) {
                throw new PayoutRequestException(KEY_REUSED, "This Idempotency-Key was already used for a different payout request");
            }
            return new InstantResult(existing, true);
        });
    }

    /**
     * The batch's payout of a merchant's payable balance in one currency, at most one per day.
     *
     * @return the payout, or empty if the merchant already has one for that day
     */
    public Optional<Payout> createBatchPayout(UUID merchantId, String currency, BigDecimal amount, String bankAccount,
                                              LocalDate batchDate, LocalDateTime cutoff) {
        if (payoutRepository.existsBatchPayout(merchantId, currency, batchDate)) {
            return Optional.empty();
        }
        LocalDateTime now = LocalDateTime.now();
        Payout payout = Payout.builder()
                .id(UUID.randomUUID())
                .merchantId(merchantId)
                .amount(amount)
                .currency(currency)
                .bankAccount(bankAccount)
                .status(PayoutStatus.PENDING)
                .trigger(PayoutTrigger.BATCH)
                .batchDate(batchDate)
                .cutoff(cutoff)
                .createdAt(now)
                .updatedAt(now)
                .build();
        try {
            return Optional.of(createAndStart(payout));
        } catch (DataIntegrityViolationException e) {
            log.info("Batch payout of {} {} for merchant {} on {} already exists", amount, currency, merchantId, batchDate);
            return Optional.empty();
        }
    }

    private Payout createAndStart(Payout payout) {
        return transactionTemplate.execute(status -> {
            Payout saved = payoutRepository.saveAndFlush(payout);
            orchestrator.start(saved);
            log.info("Payout {} created: {} {} to {} ({})", saved.publicId(), saved.getAmount(), saved.getCurrency(),
                    saved.getBankAccount(), saved.getTrigger());
            return saved;
        });
    }

    private static String hash(String... parts) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(String.join("|", parts).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
