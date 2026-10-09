package com.shylesh.processor_simulator.web;

import com.shylesh.processor_simulator.persistence.Authorization;
import com.shylesh.processor_simulator.persistence.AuthorizationRepository;
import com.shylesh.processor_simulator.persistence.Refund;
import com.shylesh.processor_simulator.persistence.RefundRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The processor's own record of a period, as acquirers publish it daily (a settlement file):
 * every authorization created, captured or voided in [from, to), and every refund. The
 * merchant's reconciliation compares it with its ledger; the processor's view is the one money
 * actually moved by.
 */
@RestController
@RequestMapping("/v1/reports")
@RequiredArgsConstructor
public class SettlementReportController {

    static final Duration MAX_PERIOD = Duration.ofDays(3);

    private final AuthorizationRepository authorizationRepository;
    private final RefundRepository refundRepository;

    public record AuthorizationLine(String id, String reference, String status, BigDecimal amount, String currency,
                                    BigDecimal capturedAmount, BigDecimal refundedAmount,
                                    LocalDateTime createdAt, LocalDateTime capturedAt, LocalDateTime voidedAt) {
    }

    public record RefundLine(String id, String authorizationId, String reference, BigDecimal amount, String currency,
                             LocalDateTime createdAt) {
    }

    public record SettlementReport(LocalDateTime from, LocalDateTime to,
                                   List<AuthorizationLine> authorizations, List<RefundLine> refunds) {
    }

    @GetMapping("/settlement")
    @Transactional(readOnly = true)
    public ResponseEntity<?> settlement(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
                                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        if (!to.isAfter(from) || Duration.between(from, to).compareTo(MAX_PERIOD) > 0) {
            return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "code", "invalid_period",
                    "message", "to must be after from, at most " + MAX_PERIOD.toDays() + " days later"));
        }

        List<Authorization> authorizations = authorizationRepository.findActiveBetween(from, to);
        List<Refund> refunds = refundRepository.findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAt(from, to);

        // refunds of authorizations outside the window still need their reference and currency
        Map<String, Authorization> byId = authorizations.stream().collect(Collectors.toMap(Authorization::getId, Function.identity()));
        refunds.stream().map(Refund::getAuthorizationId).filter(id -> !byId.containsKey(id)).distinct()
                .forEach(id -> authorizationRepository.findById(id).ifPresent(a -> byId.put(id, a)));

        return ResponseEntity.ok(new SettlementReport(from, to,
                authorizations.stream().map(a -> new AuthorizationLine(a.getId(), a.getReference(), a.getStatus().name(),
                        a.getAmount(), a.getCurrency(), a.getCapturedAmount(), a.getRefundedAmount(),
                        a.getCreatedAt(), a.getCapturedAt(), a.getVoidedAt())).toList(),
                refunds.stream().map(r -> {
                    Authorization a = byId.get(r.getAuthorizationId());
                    return new RefundLine(r.getId(), r.getAuthorizationId(), a == null ? null : a.getReference(),
                            r.getAmount(), a == null ? null : a.getCurrency(), r.getCreatedAt());
                }).toList()));
    }
}
