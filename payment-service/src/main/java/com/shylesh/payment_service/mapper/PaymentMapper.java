package com.shylesh.payment_service.mapper;

import com.shylesh.payment_service.dto.PaymentResponse;
import com.shylesh.payment_service.entity.Payment;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.springframework.stereotype.Component;


@Component
@Mapper(componentModel = "spring")
public interface PaymentMapper {

    @Mapping(target = "paymentId",
             expression = "java(\"pay_\" + payment.getId())")
    @Mapping(target = "status",
             expression = "java(payment.getStatus().name())")
    PaymentResponse toResponse(Payment payment);
}