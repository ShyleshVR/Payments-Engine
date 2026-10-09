package com.shylesh.processor_simulator.persistence;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AuthorizationRepository extends JpaRepository<Authorization, String> {

    /** Serializes capture, void and refunds of one authorization. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Authorization a WHERE a.id = :id")
    Optional<Authorization> findByIdForUpdate(@Param("id") String id);

    /** Every authorization created, captured or voided in [from, to): the settlement report. */
    @Query("""
            SELECT a FROM Authorization a
            WHERE (a.createdAt >= :from AND a.createdAt < :to)
               OR (a.capturedAt >= :from AND a.capturedAt < :to)
               OR (a.voidedAt >= :from AND a.voidedAt < :to)
            ORDER BY a.createdAt
            """)
    List<Authorization> findActiveBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
