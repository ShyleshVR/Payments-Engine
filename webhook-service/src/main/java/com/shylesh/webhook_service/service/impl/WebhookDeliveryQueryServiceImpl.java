package com.shylesh.webhook_service.service.impl;

import com.shylesh.webhook_service.dto.WebhookDeliveryAttemptResponse;
import com.shylesh.webhook_service.dto.WebhookDeliveryResponse;
import com.shylesh.webhook_service.persistence.WebhookDelivery;
import com.shylesh.webhook_service.persistence.WebhookDeliveryAttempt;
import com.shylesh.webhook_service.persistence.WebhookDeliveryAttemptRepository;
import com.shylesh.webhook_service.persistence.WebhookDeliveryRepository;
import com.shylesh.webhook_service.service.WebhookDeliveryQueryService;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WebhookDeliveryQueryServiceImpl implements WebhookDeliveryQueryService {

    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookDeliveryAttemptRepository attemptRepository;

    @Override
    public List<WebhookDeliveryResponse> findByPayment(UUID merchantId, UUID paymentId) {
        return withAttempts(deliveryRepository.findByPaymentIdAndMerchantIdOrderByCreatedAtAsc(paymentId, merchantId));
    }

    @Override
    public List<WebhookDeliveryResponse> findByPayout(UUID merchantId, UUID payoutId) {
        return withAttempts(deliveryRepository.findByPayoutIdAndMerchantIdOrderByCreatedAtAsc(payoutId, merchantId));
    }

    private List<WebhookDeliveryResponse> withAttempts(List<WebhookDelivery> deliveries) {
        if (deliveries.isEmpty()) {
            return List.of();
        }

        Map<UUID, List<WebhookDeliveryAttemptResponse>> attemptsByDelivery = attemptRepository
                .findByDeliveryIdInOrderByAttemptNumberAsc(deliveries.stream().map(WebhookDelivery::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(
                        WebhookDeliveryAttempt::getDeliveryId,
                        Collectors.mapping(WebhookDeliveryAttemptResponse::of, Collectors.toList())
                ));

        return deliveries.stream()
                .map(delivery -> WebhookDeliveryResponse.of(delivery, attemptsByDelivery.getOrDefault(delivery.getId(), List.of())))
                .toList();
    }
}
