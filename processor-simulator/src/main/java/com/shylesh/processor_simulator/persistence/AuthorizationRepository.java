package com.shylesh.processor_simulator.persistence;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Limit;
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

    // The settlement report lists every authorization created, captured or voided in [from, to),
    // a page at a time. One query for all three would have to rescan old rows on every page, so
    // the report walks three index ranges in turn; together they return each row exactly once.
    // "after" is the last row of the previous page: the scan starts there (afterTime is also the
    // range's lower bound, so the index is entered at the cursor, not at the start of the period).

    /** Created in the period, from the cursor on, by (createdAt, id). */
    @Query("""
            SELECT a FROM Authorization a
            WHERE a.createdAt >= :afterTime AND a.createdAt < :to
              AND (a.createdAt > :afterTime OR a.id > :afterId)
            ORDER BY a.createdAt, a.id
            """)
    List<Authorization> pageCreatedIn(@Param("to") LocalDateTime to, @Param("afterTime") LocalDateTime afterTime,
                                      @Param("afterId") String afterId, Limit limit);

    /** Captured in the period but created before it (the first query has the rest), by (capturedAt, id). */
    @Query("""
            SELECT a FROM Authorization a
            WHERE a.capturedAt >= :afterTime AND a.capturedAt < :to AND a.createdAt < :from
              AND (a.capturedAt > :afterTime OR a.id > :afterId)
            ORDER BY a.capturedAt, a.id
            """)
    List<Authorization> pageCapturedIn(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                       @Param("afterTime") LocalDateTime afterTime, @Param("afterId") String afterId,
                                       Limit limit);

    /** Voided in the period, created before it and not captured in it, by (voidedAt, id). */
    @Query("""
            SELECT a FROM Authorization a
            WHERE a.voidedAt >= :afterTime AND a.voidedAt < :to AND a.createdAt < :from
              AND (a.capturedAt IS NULL OR a.capturedAt < :from OR a.capturedAt >= :to)
              AND (a.voidedAt > :afterTime OR a.id > :afterId)
            ORDER BY a.voidedAt, a.id
            """)
    List<Authorization> pageVoidedIn(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                     @Param("afterTime") LocalDateTime afterTime, @Param("afterId") String afterId,
                                     Limit limit);
}
