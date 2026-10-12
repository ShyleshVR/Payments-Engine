package com.shylesh.processor_simulator.web;

import com.shylesh.processor_simulator.persistence.Authorization;
import com.shylesh.processor_simulator.persistence.AuthorizationRepository;
import com.shylesh.processor_simulator.persistence.Refund;
import com.shylesh.processor_simulator.persistence.RefundRepository;
import com.shylesh.processor_simulator.persistence.Transfer;
import com.shylesh.processor_simulator.persistence.TransferRepository;
import com.shylesh.processor_simulator.web.ReportCursor.Pass;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Limit;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The processor's own record of a period, as acquirers publish it daily (a settlement file):
 * every authorization created, captured or voided in [from, to), every refund, and every payout
 * transfer created, paid, failed or returned. The merchant's reconciliation compares it with its
 * ledger; the processor's view is the one money actually moved by.
 *
 * Each list is its own endpoint and is read a page at a time: a busy day holds millions of rows,
 * and a report built in memory for one response took the processor down. A page's "next" is
 * sent back as "after" to get the following page; null means the list is complete.
 */
@RestController
@RequestMapping("/v1/reports/settlement")
@RequiredArgsConstructor
public class SettlementReportController {

    static final Duration MAX_PERIOD = Duration.ofDays(3);
    static final int MAX_LIMIT = 1000;

    private final AuthorizationRepository authorizationRepository;
    private final RefundRepository refundRepository;
    private final TransferRepository transferRepository;

    public record AuthorizationLine(String id, String reference, String status, BigDecimal amount, String currency,
                                    BigDecimal capturedAmount, BigDecimal refundedAmount,
                                    LocalDateTime createdAt, LocalDateTime capturedAt, LocalDateTime voidedAt) {
    }

    public record RefundLine(String id, String authorizationId, String reference, BigDecimal amount, String currency,
                             LocalDateTime createdAt) {
    }

    public record TransferLine(String id, String reference, String status, BigDecimal amount, String currency,
                               String failureCode, LocalDateTime createdAt, LocalDateTime paidAt,
                               LocalDateTime failedAt, LocalDateTime returnedAt) {
    }

    public record Page<T>(List<T> items, String next) {
    }

    @GetMapping("/authorizations")
    @Transactional(readOnly = true)
    public ResponseEntity<?> authorizations(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
                                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
                                            @RequestParam(required = false) String after,
                                            @RequestParam(defaultValue = "1000") int limit) {
        if (invalid(from, to)) {
            return invalidPeriod();
        }
        int size = size(limit);
        ReportCursor cursor = after == null ? ReportCursor.startOf(Pass.CREATED, from) : ReportCursor.decode(after, from, to);

        // fill the page from the current pass; when a pass runs dry, go on with the next one
        List<AuthorizationLine> items = new ArrayList<>(size);
        while (cursor != null && items.size() < size) {
            Limit wanted = Limit.of(size - items.size());
            Pass pass = cursor.pass();
            List<Authorization> rows = switch (pass) {
                case CREATED -> authorizationRepository.pageCreatedIn(to, cursor.time(), cursor.id(), wanted);
                case CAPTURED -> authorizationRepository.pageCapturedIn(from, to, cursor.time(), cursor.id(), wanted);
                case VOIDED -> authorizationRepository.pageVoidedIn(from, to, cursor.time(), cursor.id(), wanted);
            };
            rows.forEach(a -> items.add(new AuthorizationLine(a.getId(), a.getReference(), a.getStatus().name(),
                    a.getAmount(), a.getCurrency(), a.getCapturedAmount(), a.getRefundedAmount(),
                    a.getCreatedAt(), a.getCapturedAt(), a.getVoidedAt())));
            if (rows.size() < wanted.max()) {
                cursor = pass.next() == null ? null : ReportCursor.startOf(pass.next(), from);
            } else {
                Authorization last = rows.getLast();
                LocalDateTime sortedBy = switch (pass) {
                    case CREATED -> last.getCreatedAt();
                    case CAPTURED -> last.getCapturedAt();
                    case VOIDED -> last.getVoidedAt();
                };
                cursor = new ReportCursor(pass, sortedBy, last.getId());
            }
        }
        return ResponseEntity.ok(new Page<>(items, cursor == null ? null : cursor.encode()));
    }

    @GetMapping("/refunds")
    @Transactional(readOnly = true)
    public ResponseEntity<?> refunds(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
                                     @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
                                     @RequestParam(required = false) String after,
                                     @RequestParam(defaultValue = "1000") int limit) {
        if (invalid(from, to)) {
            return invalidPeriod();
        }
        int size = size(limit);
        ReportCursor cursor = after == null ? ReportCursor.startOf(Pass.CREATED, from) : ReportCursor.decode(after, from, to);
        List<Refund> rows = refundRepository.pageCreatedIn(to, cursor.time(), cursor.id(), Limit.of(size));

        // a refund's reference and currency are its authorization's, whenever that was created
        Map<String, Authorization> authorizations = authorizationRepository
                .findAllById(rows.stream().map(Refund::getAuthorizationId).distinct().toList()).stream()
                .collect(Collectors.toMap(Authorization::getId, Function.identity()));
        List<RefundLine> items = rows.stream().map(r -> {
            Authorization a = authorizations.get(r.getAuthorizationId());
            return new RefundLine(r.getId(), r.getAuthorizationId(), a == null ? null : a.getReference(),
                    r.getAmount(), a == null ? null : a.getCurrency(), r.getCreatedAt());
        }).toList();
        String next = rows.size() < size ? null
                : new ReportCursor(Pass.CREATED, rows.getLast().getCreatedAt(), rows.getLast().getId()).encode();
        return ResponseEntity.ok(new Page<>(items, next));
    }

    @GetMapping("/transfers")
    @Transactional(readOnly = true)
    public ResponseEntity<?> transfers(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
                                       @RequestParam(required = false) String after,
                                       @RequestParam(defaultValue = "1000") int limit) {
        if (invalid(from, to)) {
            return invalidPeriod();
        }
        int size = size(limit);
        // transfers are paged by id alone; the cursor's time is only checked against the period
        String afterId = after == null ? "" : ReportCursor.decode(after, from, to).id();
        List<Transfer> rows = transferRepository.pageActiveIn(from, to, afterId, Limit.of(size));

        List<TransferLine> items = rows.stream().map(t -> new TransferLine(t.getId(), t.getReference(), t.getStatus().name(),
                t.getAmount(), t.getCurrency(), t.getFailureCode(), t.getCreatedAt(), t.getPaidAt(),
                t.getFailedAt(), t.getReturnedAt())).toList();
        String next = rows.size() < size ? null : new ReportCursor(Pass.CREATED, from, rows.getLast().getId()).encode();
        return ResponseEntity.ok(new Page<>(items, next));
    }

    @ExceptionHandler(ReportCursor.Invalid.class)
    ResponseEntity<Map<String, String>> invalidCursor() {
        return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "code", "invalid_cursor",
                "message", "after must be the next value of the previous page of the same period"));
    }

    private static boolean invalid(LocalDateTime from, LocalDateTime to) {
        return !to.isAfter(from) || Duration.between(from, to).compareTo(MAX_PERIOD) > 0;
    }

    private static ResponseEntity<Map<String, String>> invalidPeriod() {
        return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "code", "invalid_period",
                "message", "to must be after from, at most " + MAX_PERIOD.toDays() + " days later"));
    }

    /** A page holds between 1 and MAX_LIMIT rows, whatever was asked for. */
    private static int size(int limit) {
        return Math.max(1, Math.min(MAX_LIMIT, limit));
    }
}
