package com.shylesh.reconciliation_service.service;

import java.time.LocalDate;

/** Another run of the same day hasn't finished (one at a time per day, across replicas). */
public class RunInProgressException extends RuntimeException {

    public RunInProgressException(LocalDate date) {
        super("A reconciliation of " + date + " is already running");
    }
}
