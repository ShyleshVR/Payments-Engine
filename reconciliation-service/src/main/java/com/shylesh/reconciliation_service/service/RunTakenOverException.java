package com.shylesh.reconciliation_service.service;

import com.shylesh.reconciliation_service.persistence.ReconciliationRun;

/**
 * The run is no longer RUNNING in the database: it was silent for so long (a stalled pod) that
 * another attempt ended it and took its day over. It must stop and leave the result to that one.
 */
class RunTakenOverException extends RuntimeException {

    RunTakenOverException(ReconciliationRun run) {
        super("Run " + run.getId() + " of " + run.getBusinessDate() + " was taken over by another run");
    }
}
