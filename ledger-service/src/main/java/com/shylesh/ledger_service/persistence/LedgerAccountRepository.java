package com.shylesh.ledger_service.persistence;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LedgerAccountRepository extends JpaRepository<LedgerAccount, UUID> {

    List<LedgerAccount> findByOwnerTypeAndOwnerId(LedgerAccountType ownerType, UUID ownerId);

    Optional<LedgerAccount> findByOwnerTypeAndOwnerIdAndCurrency(
            LedgerAccountType ownerType, UUID ownerId, String currency
    );

    /**
     * Serializes concurrent find-or-create attempts for the same owner key across
     * transactions/instances. The lock is held for the rest of the current transaction and
     * released automatically on commit/rollback, so a second caller resolving the same key
     * blocks here until the first has committed the row it created, rather than racing the
     * unique constraint.
     */
    /** Serializes balance checks on one account (two refunds can't both spend the same balance). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM LedgerAccount a WHERE a.id = :id")
    Optional<LedgerAccount> findByIdForUpdate(@Param("id") UUID id);

    @Query(value = "SELECT pg_advisory_xact_lock(hashtext(:lockKey)::bigint)", nativeQuery = true)
    void acquireCreationLock(@Param("lockKey") String lockKey);
}
