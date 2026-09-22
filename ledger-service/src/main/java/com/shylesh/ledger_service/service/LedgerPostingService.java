package com.shylesh.ledger_service.service;

import com.shylesh.ledger_service.event.EventEnvelope;

public interface LedgerPostingService {

    void handle(EventEnvelope envelope);
}
