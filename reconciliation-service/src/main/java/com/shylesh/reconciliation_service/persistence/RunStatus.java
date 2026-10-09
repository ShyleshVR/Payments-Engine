package com.shylesh.reconciliation_service.persistence;

public enum RunStatus {
    RUNNING,
    COMPLETED,
    /** A source couldn't be read (or another error); retried by the hourly catch-up. */
    FAILED
}
