package com.shylesh.processor_simulator.persistence;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AuthorizationRepository extends JpaRepository<Authorization, String> {

    /** Serializes capture, void and refunds of one authorization. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Authorization a WHERE a.id = :id")
    Optional<Authorization> findByIdForUpdate(@Param("id") String id);
}
