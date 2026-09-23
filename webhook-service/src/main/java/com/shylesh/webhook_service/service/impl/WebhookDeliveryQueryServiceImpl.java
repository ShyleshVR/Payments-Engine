package com.shylesh.webhook_service.service.impl;

import com.shylesh.webhook_service.dto.WebhookDeliveryAttemptResponse;
import com.shylesh.webhook_service.dto.WebhookDeliveryResponse;
import com.shylesh.webhook_service.persistence.WebhookDelivery;
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
public class WebhookDeliveryQueryServiceImpl implements WebhookDeliveryQueryService {

    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookDeliveryAttemptRepository attemptRepository;

    @Override
    @Transactional(readOnly = true)
    public List<WebhookDeliveryResponse> findByPaymentId(UUID paymentId) {
        List<WebhookDelivery> deliveries = deliveryRepository.findByPaymentIdOrderByCreatedAtAsc(paymentId);
        if (deliveries.isEmpty()) {
            return List.of();
        }

        // One query for all attempts rather than one per delivery.
        Map<UUID, List<WebhookDeliveryAttemptResponse>> attemptsByDelivery = attemptRepository
                .findByDeliveryIdInOrderByAttemptNumberAsc(deliveries.stream().map(WebhookDelivery::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(
                        attempt -> attempt.getDeliveryId(),
                        Collectors.mapping(WebhookDeliveryAttemptResponse::from, Collectors.toList())
                ));

        return deliveries.stream()
                .map(delivery -> WebhookDeliveryResponse.from(
                        delivery,
                        attemptsByDelivery.getOrDefault(delivery.getId(), List.of())
                ))
                .toList();
    }
}
