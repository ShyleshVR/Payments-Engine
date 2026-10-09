package com.shylesh.ledger_service.command;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A command's data. commandId identifies the command across re-sends (the orchestrator re-sends
 * the same id when a reply is late), sagaId ties a refund's hold, release and final posting
 * together. amount is a BigDecimal parsed straight from the JSON text (exact).
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class LedgerCommand {

    private UUID commandId;
    private String commandType;
    private UUID sagaId;
    private UUID paymentId;
    private UUID merchantId;
    private BigDecimal amount;
    private String currency;
}
