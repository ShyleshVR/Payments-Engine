package com.shylesh.processor_simulator.persistence;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface OperationRepository extends JpaRepository<Operation, String> {

    /**
     * Creates the key's row unless it exists. If another transaction has inserted the same key
     * and not committed yet, Postgres makes this wait for it, so two concurrent requests with
     * one key are serialized: the second then finds the first one's stored response.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO operations (idempotency_key, operation_type, request_hash, reversed, created_at)
            VALUES (:key, :type, :hash, :reversed, :now)
            ON CONFLICT (idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("key") String key, @Param("type") String type, @Param("hash") String hash,
                       @Param("reversed") boolean reversed, @Param("now") LocalDateTime now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Operation o WHERE o.idempotencyKey = :key")
    Optional<Operation> findByKeyForUpdate(@Param("key") String key);
}
