package com.shylesh.payout_service.web;

import com.shylesh.payout_service.payout.PayoutService;
import com.shylesh.payout_service.security.CurrentMerchant;
import com.shylesh.payout_service.security.CurrentMerchantArgumentResolver;
import com.shylesh.payout_service.security.Scopes;
import com.shylesh.payout_service.web.PayoutViews.BalanceView;
import com.shylesh.payout_service.web.PayoutViews.DestinationRequest;
import com.shylesh.payout_service.web.PayoutViews.DestinationView;
import com.shylesh.payout_service.web.PayoutViews.InstantPayoutRequest;
import com.shylesh.payout_service.web.PayoutViews.PayoutView;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Merchants: where their payouts go, what they can be paid now, instant payouts, and their payout
 * history. Operators (payouts:operate) can read any payout.
 */
@Validated
@RestController
@RequestMapping("/api/v1/payouts")
@RequiredArgsConstructor
public class PayoutController {

    static final int MAX_LIST = 100;

    private final PayoutService payoutService;
    private final PayoutQueries queries;

    @PutMapping("/destination")
    @PreAuthorize("hasAuthority(T(com.shylesh.payout_service.security.Scopes).PAYOUTS_WRITE)")
    public DestinationView setDestination(@CurrentMerchant UUID merchantId, @Valid @RequestBody DestinationRequest request) {
        return DestinationView.of(payoutService.setDestination(merchantId, request.bankAccount()));
    }

    @GetMapping("/destination")
    @PreAuthorize("hasAnyAuthority(T(com.shylesh.payout_service.security.Scopes).PAYOUTS_READ, T(com.shylesh.payout_service.security.Scopes).PAYOUTS_WRITE)")
    public ResponseEntity<DestinationView> destination(@CurrentMerchant UUID merchantId) {
        return payoutService.destination(merchantId).map(DestinationView::of).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** What could be paid out right now: settlements older than the payout delay, minus what already left. */
    @GetMapping("/balance")
    @PreAuthorize("hasAuthority(T(com.shylesh.payout_service.security.Scopes).PAYOUTS_READ)")
    public BalanceView balance(@CurrentMerchant UUID merchantId, @RequestParam @Pattern(regexp = "[A-Z]{3}") String currency) {
        LocalDateTime now = LocalDateTime.now();
        return new BalanceView(currency, payoutService.payable(merchantId, currency), payoutService.cutoff(now));
    }

    /**
     * An instant payout of part of the payable balance. 202: the payout runs asynchronously (the
     * bank takes a while); poll it, or listen for its webhook. A retry with the same
     * Idempotency-Key returns the same payout (200, Idempotent-Replayed: true).
     */
    @PostMapping
    @PreAuthorize("hasAuthority(T(com.shylesh.payout_service.security.Scopes).PAYOUTS_WRITE)")
    public ResponseEntity<PayoutView> requestPayout(@CurrentMerchant UUID merchantId,
                                                    @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9:._-]{1,100}") String idempotencyKey,
                                                    @Valid @RequestBody InstantPayoutRequest request) {
        PayoutService.InstantResult result = payoutService.requestInstant(merchantId, idempotencyKey, request.amount(), request.currency());
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .header("Idempotent-Replayed", String.valueOf(result.replayed()))
                .body(PayoutView.of(result.payout()));
    }

    /** The merchant's payouts, newest first; for an operator, everyone's. */
    @GetMapping
    @PreAuthorize("hasAnyAuthority(T(com.shylesh.payout_service.security.Scopes).PAYOUTS_READ, T(com.shylesh.payout_service.security.Scopes).PAYOUTS_OPERATE)")
    public List<PayoutView> list(@RequestParam(defaultValue = "20") int limit, Authentication authentication) {
        int bounded = Math.max(1, Math.min(limit, MAX_LIST));
        return operator(authentication)
                ? queries.all(bounded)
                : queries.own(CurrentMerchantArgumentResolver.merchantId(authentication), bounded);
    }

    /** A merchant sees only its own payouts (404 for anyone else's); an operator sees any. */
    @GetMapping("/{payoutId}")
    @PreAuthorize("hasAnyAuthority(T(com.shylesh.payout_service.security.Scopes).PAYOUTS_READ, T(com.shylesh.payout_service.security.Scopes).PAYOUTS_OPERATE)")
    public PayoutView get(@PathVariable String payoutId, Authentication authentication) {
        UUID id = PayoutQueries.parseId(payoutId);
        return operator(authentication)
                ? queries.any(id)
                : queries.own(CurrentMerchantArgumentResolver.merchantId(authentication), id);
    }

    private static boolean operator(Authentication authentication) {
        return authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).anyMatch(Scopes.PAYOUTS_OPERATE::equals);
    }
}
