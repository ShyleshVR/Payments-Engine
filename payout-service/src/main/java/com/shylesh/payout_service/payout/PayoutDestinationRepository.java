package com.shylesh.payout_service.payout;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PayoutDestinationRepository extends JpaRepository<PayoutDestination, UUID> {
}
