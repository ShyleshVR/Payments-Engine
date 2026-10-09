package com.shylesh.processor_simulator.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface RefundRepository extends JpaRepository<Refund, String> {

    List<Refund> findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAt(LocalDateTime from, LocalDateTime to);
}
