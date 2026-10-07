package com.shylesh.payment_service.event;

public final class Topics {

    public static final String PAYMENT_CREATED = "payment-created";

    /**
     * Where consumers dead-letter payment events. Needs at least as many partitions as
     * PAYMENT_CREATED: the recoverer writes to the same partition number.
     */
    public static final String PAYMENT_CREATED_DLT = PAYMENT_CREATED + ".DLT";

    private Topics() {}
}