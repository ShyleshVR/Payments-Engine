package com.shylesh.payment_service.event;

import com.shylesh.payment_service.entity.Payment;

public interface PaymentEventPublisher {

    void publishPaymentCreated(Payment payment);

}
