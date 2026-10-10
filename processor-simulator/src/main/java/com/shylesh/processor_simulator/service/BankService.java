package com.shylesh.processor_simulator.service;

import com.shylesh.processor_simulator.config.ProcessorProperties;
import com.shylesh.processor_simulator.persistence.Transfer;
import com.shylesh.processor_simulator.persistence.TransferRepository;
import com.shylesh.processor_simulator.persistence.TransferStatus;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The bank side of payouts. A transfer is accepted (PENDING) or rejected at once; the bank's
 * clock (BankClock) later settles it (PAID) or fails it, and may send a paid one back (RETURNED).
 * The caller learns the outcome by polling, as with real bank rails.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BankService {

    private static final int CLOCK_BATCH = 200;

    private final TransferRepository transferRepository;
    private final ProcessorProperties properties;
    private final MeterRegistry meterRegistry;

    /** Runs inside IdempotentExecutor's transaction: the transfer and the stored response commit together. */
    public ProcessorResult transfer(String bankAccount, BigDecimal amount, String currency, String reference) {
        Optional<BankAccountBehaviour> behaviour = BankAccountBehaviour.of(bankAccount);
        if (behaviour.isEmpty() || behaviour.get() == BankAccountBehaviour.INVALID) {
            count("REJECTED");
            return ProcessorResult.declined(BankAccountBehaviour.INVALID_ACCOUNT, "The bank can't route a transfer to this account", null);
        }
        LocalDateTime now = LocalDateTime.now();
        Transfer transfer = transferRepository.save(Transfer.builder()
                .id("tr_" + UUID.randomUUID().toString().replace("-", ""))
                .bankAccount(bankAccount)
                .amount(amount)
                .currency(currency)
                .reference(reference)
                .status(TransferStatus.PENDING)
                .createdAt(now)
                .updatedAt(now)
                .nextTransitionAt(now.plus(behaviour.get() == BankAccountBehaviour.SLOW
                        ? properties.bank().slowSettleDelay() : properties.bank().settleDelay()))
                .build());
        count(TransferStatus.PENDING.name());
        return ProcessorResult.of(201, body(transfer), null);
    }

    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> find(String transferId) {
        return transferRepository.findById(transferId).map(BankService::body);
    }

    /** Moves due transfers on: pending ones settle or fail, paid returning ones come back. */
    @Transactional
    public int advance(LocalDateTime now) {
        List<Transfer> due = transferRepository.lockDue(now, CLOCK_BATCH);
        for (Transfer transfer : due) {
            BankAccountBehaviour behaviour = BankAccountBehaviour.of(transfer.getBankAccount()).orElseThrow();
            if (transfer.getStatus() == TransferStatus.PENDING) {
                if (behaviour == BankAccountBehaviour.CLOSED) {
                    transfer.fail(BankAccountBehaviour.ACCOUNT_CLOSED, now);
                } else {
                    transfer.pay(now, behaviour == BankAccountBehaviour.RETURNED ? now.plus(properties.bank().returnDelay()) : null);
                }
            } else if (transfer.getStatus() == TransferStatus.PAID) {
                transfer.sendBack(BankAccountBehaviour.ACCOUNT_FROZEN, now);
            }
            count(transfer.getStatus().name());
            log.info("Transfer {} is now {} (reference {})", transfer.getId(), transfer.getStatus(), transfer.getReference());
        }
        return due.size();
    }

    static Map<String, Object> body(Transfer transfer) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", transfer.getId());
        body.put("status", transfer.getStatus().name());
        body.put("bankAccount", transfer.getBankAccount());
        body.put("amount", transfer.getAmount());
        body.put("currency", transfer.getCurrency());
        body.put("reference", transfer.getReference());
        body.put("failureCode", transfer.getFailureCode());
        body.put("createdAt", transfer.getCreatedAt());
        body.put("paidAt", transfer.getPaidAt());
        body.put("failedAt", transfer.getFailedAt());
        body.put("returnedAt", transfer.getReturnedAt());
        return body;
    }

    private void count(String status) {
        Counter.builder("bank.transfers").tag("status", status).register(meterRegistry).increment();
    }
}
