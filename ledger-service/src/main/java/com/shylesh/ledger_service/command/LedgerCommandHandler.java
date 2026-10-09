package com.shylesh.ledger_service.command;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shylesh.ledger_service.config.LedgerTopics;
import com.shylesh.ledger_service.outbox.OutboxEvent;
import com.shylesh.ledger_service.outbox.OutboxEventRepository;
import com.shylesh.ledger_service.outbox.OutboxEventStatus;
import com.shylesh.ledger_service.persistence.*;
import com.shylesh.ledger_service.service.impl.LedgerAccountResolver;
import com.shylesh.ledger_service.service.impl.LedgerPoster;
import com.shylesh.ledger_service.tracing.TraceContext;

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
 * Executes saga commands. Each command is handled in one transaction that posts (or rejects),
 * records the command as processed, and writes the reply to the outbox, so a posting and its
 * reply are committed together or not at all. A command seen before posts nothing and gets its
 * stored reply again: the orchestrator re-sends a command whose reply is late, and Kafka may
 * redeliver.
 *
 * Accounts per merchant and currency: MERCHANT (available balance) and MERCHANT_REFUND_RESERVE
 * (refunds in progress). A refund is held first, then either released (processor refused) or
 * finalized (processor refunded); a hold only succeeds if the available balance covers it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerCommandHandler {

    static final String INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS";
    static final String NO_ACTIVE_HOLD = "NO_ACTIVE_HOLD";

    private final ProcessedCommandRepository processedCommandRepository;
    private final LedgerAccountResolver accountResolver;
    private final LedgerAccountRepository accountRepository;
    private final LedgerEntryRepository entryRepository;
    private final LedgerTransactionRepository transactionRepository;
    private final LedgerPoster poster;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final TraceContext traceContext;

    @Transactional
    public LedgerReply handle(LedgerCommand command) {
        Optional<ProcessedCommand> processed = processedCommandRepository.findById(command.getCommandId());
        if (processed.isPresent()) {
            LedgerReply reply = read(processed.get().getReply());
            enqueueReply(reply);
            count(command, reply, true);
            log.info("Duplicate ledger command, replying again. commandId={}, outcome={}", command.getCommandId(), reply.outcome());
            return reply;
        }

        LedgerReply reply = switch (LedgerCommandType.valueOf(command.getCommandType())) {
            case SETTLE_PAYMENT -> settle(command);
            case HOLD_REFUND -> hold(command);
            case RELEASE_HOLD -> releaseHold(command);
            case FINALIZE_REFUND -> finalizeRefund(command);
        };

        processedCommandRepository.save(ProcessedCommand.builder()
                .commandId(command.getCommandId())
                .commandType(command.getCommandType())
                .paymentId(command.getPaymentId())
                .reply(write(reply))
                .processedAt(LocalDateTime.now())
                .build());
        enqueueReply(reply);
        count(command, reply, false);
        log.info("Handled ledger command. commandId={}, type={}, outcome={}, reason={}",
                command.getCommandId(), command.getCommandType(), reply.outcome(), reply.reason());
        return reply;
    }

    /** Captured payment: debit platform clearing, credit the merchant. Once per payment. */
    private LedgerReply settle(LedgerCommand command) {
        Optional<LedgerTransaction> existing = transactionRepository
                .findFirstByPaymentIdAndType(command.getPaymentId(), LedgerTransactionType.SETTLEMENT);
        if (existing.isPresent()) {
            // a different command id for a payment already settled: never post a second settlement
            return LedgerReply.succeeded(command, existing.get().getId());
        }
        LedgerAccount platform = accountResolver.resolve(LedgerAccountType.PLATFORM_CLEARING, null, command.getCurrency());
        LedgerAccount merchant = merchantAccount(command);
        LedgerTransaction transaction = poster.post(command.getCommandId(), command.getPaymentId(), command.getSagaId(),
                LedgerTransactionType.SETTLEMENT, platform.getId(), merchant.getId(), command.getAmount(), command.getCurrency());
        return LedgerReply.succeeded(command, transaction.getId());
    }

    /**
     * Moves the refund amount out of the merchant's available balance, if it covers it. The
     * merchant account row is locked first, so two concurrent refunds can't both pass the check
     * against the same balance.
     */
    private LedgerReply hold(LedgerCommand command) {
        LedgerAccount merchant = merchantAccount(command);
        accountRepository.findByIdForUpdate(merchant.getId()).orElseThrow();
        BigDecimal available = entryRepository.sumBalanceByAccountId(merchant.getId());
        if (available.compareTo(command.getAmount()) < 0) {
            return LedgerReply.rejected(command, INSUFFICIENT_FUNDS);
        }
        LedgerAccount reserve = reserveAccount(command);
        LedgerTransaction transaction = poster.post(command.getCommandId(), command.getPaymentId(), command.getSagaId(),
                LedgerTransactionType.REFUND_HOLD, merchant.getId(), reserve.getId(), command.getAmount(), command.getCurrency());
        return LedgerReply.succeeded(command, transaction.getId());
    }

    /** Compensation: the processor refused the refund, so the held amount goes back. */
    private LedgerReply releaseHold(LedgerCommand command) {
        if (!hasActiveHold(command)) {
            return LedgerReply.rejected(command, NO_ACTIVE_HOLD);
        }
        LedgerTransaction transaction = poster.post(command.getCommandId(), command.getPaymentId(), command.getSagaId(),
                LedgerTransactionType.REFUND_HOLD_RELEASE, reserveAccount(command).getId(), merchantAccount(command).getId(),
                command.getAmount(), command.getCurrency());
        return LedgerReply.succeeded(command, transaction.getId());
    }

    /** The processor refunded: the held amount leaves through platform clearing. */
    private LedgerReply finalizeRefund(LedgerCommand command) {
        if (!hasActiveHold(command)) {
            return LedgerReply.rejected(command, NO_ACTIVE_HOLD);
        }
        LedgerAccount platform = accountResolver.resolve(LedgerAccountType.PLATFORM_CLEARING, null, command.getCurrency());
        LedgerTransaction transaction = poster.post(command.getCommandId(), command.getPaymentId(), command.getSagaId(),
                LedgerTransactionType.REFUND, reserveAccount(command).getId(), platform.getId(),
                command.getAmount(), command.getCurrency());
        return LedgerReply.succeeded(command, transaction.getId());
    }

    /**
     * Release and finalize need this saga's hold, not yet released or finalized, of the same
     * amount. Anything else means the orchestrator and the ledger disagree, which must not be
     * papered over: the command is rejected and the saga stops for an operator.
     */
    private boolean hasActiveHold(LedgerCommand command) {
        List<LedgerTransaction> sagaTransactions = transactionRepository.findBySagaIdOrderByCreatedAtAsc(command.getSagaId());
        Optional<LedgerTransaction> hold = sagaTransactions.stream()
                .filter(t -> t.getType() == LedgerTransactionType.REFUND_HOLD)
                .findFirst();
        boolean settled = sagaTransactions.stream().anyMatch(t ->
                t.getType() == LedgerTransactionType.REFUND_HOLD_RELEASE || t.getType() == LedgerTransactionType.REFUND);
        if (hold.isEmpty() || settled) {
            return false;
        }
        BigDecimal heldAmount = entryRepository.findByTransactionIdInOrderByCreatedAtAsc(List.of(hold.get().getId()))
                .getFirst().getAmount();
        return heldAmount.compareTo(command.getAmount()) == 0;
    }

    private LedgerAccount merchantAccount(LedgerCommand command) {
        return accountResolver.resolve(LedgerAccountType.MERCHANT, command.getMerchantId(), command.getCurrency());
    }

    private LedgerAccount reserveAccount(LedgerCommand command) {
        return accountResolver.resolve(LedgerAccountType.MERCHANT_REFUND_RESERVE, command.getMerchantId(), command.getCurrency());
    }

    private void enqueueReply(LedgerReply reply) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        UUID messageId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        envelope.put("eventId", messageId);
        envelope.put("eventType", "LEDGER_REPLY");
        envelope.put("occurredAt", now);
        envelope.put("data", reply);
        outboxEventRepository.save(OutboxEvent.builder()
                .id(messageId)
                .aggregateId(reply.paymentId())
                .topic(LedgerTopics.REPLIES)
                .eventType("LEDGER_REPLY")
                .payload(write(envelope))
                .status(OutboxEventStatus.PENDING)
                .traceParent(traceContext.current())
                .createdAt(now)
                .build());
    }

    private void count(LedgerCommand command, LedgerReply reply, boolean duplicate) {
        Counter.builder("ledger.commands")
                .tag("type", command.getCommandType())
                .tag("outcome", reply.outcome())
                .tag("reason", reply.reason() == null ? "none" : reply.reason())
                .tag("duplicate", String.valueOf(duplicate))
                .register(meterRegistry)
                .increment();
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unserializable ledger reply", e);
        }
    }

    private LedgerReply read(String json) {
        try {
            return objectMapper.readValue(json, LedgerReply.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable stored ledger reply", e);
        }
    }
}
