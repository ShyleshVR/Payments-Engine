package com.shylesh.ledger_service.command;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A command's data. commandId identifies the command across re-sends (the orchestrator re-sends
 * the same id when a reply is late), sagaId ties a refund's or payout's hold, release and final
 * posting together. amount is a BigDecimal parsed straight from the JSON text (exact).
 *
 * A payment command carries paymentId, a payout command payoutId. HOLD_PAYOUT also carries the
 * cutoff of the payable balance: settlements after it are not paid out yet.
 */
@Getter
@NoArgsConstructor
public class LedgerCommand {

    private UUID commandId;
    private String commandType;
    private UUID sagaId;
    private UUID paymentId;
    private UUID payoutId;
    private UUID merchantId;
    private BigDecimal amount;
    private String currency;
    private LocalDateTime cutoff;

    /** A payment or refund command. */
    public LedgerCommand(UUID commandId, String commandType, UUID sagaId, UUID paymentId, UUID merchantId,
                         BigDecimal amount, String currency) {
        this(commandId, commandType, sagaId, paymentId, null, merchantId, amount, currency, null);
    }

    public LedgerCommand(UUID commandId, String commandType, UUID sagaId, UUID paymentId, UUID payoutId,
                         UUID merchantId, BigDecimal amount, String currency, LocalDateTime cutoff) {
        this.commandId = commandId;
        this.commandType = commandType;
        this.sagaId = sagaId;
        this.paymentId = paymentId;
        this.payoutId = payoutId;
        this.merchantId = merchantId;
        this.amount = amount;
        this.currency = currency;
        this.cutoff = cutoff;
    }

    public static LedgerCommand payout(UUID commandId, LedgerCommandType type, UUID sagaId, UUID payoutId, UUID merchantId,
                                       BigDecimal amount, String currency, LocalDateTime cutoff) {
        return new LedgerCommand(commandId, type.name(), sagaId, null, payoutId, merchantId, amount, currency, cutoff);
    }

    public LedgerCommandType type() {
        return LedgerCommandType.valueOf(commandType);
    }

    /** The payment or payout the command is about: the key of its reply. */
    public UUID subjectId() {
        return paymentId != null ? paymentId : payoutId;
    }
}
