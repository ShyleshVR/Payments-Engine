package com.shylesh.reconciliation_service.persistence;

public enum RunTrigger {
    /** The daily job or its catch-up. */
    SCHEDULED,
    /** An operator, through the API. */
    MANUAL
}
