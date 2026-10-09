package com.shylesh.payment_service.saga;

public enum SagaType {

    /** authorize -> (MANUAL: wait for capture) -> capture -> settle in the ledger */
    PAYMENT,
    /** hold in the ledger -> refund at the processor -> finalize in the ledger */
    REFUND

}
