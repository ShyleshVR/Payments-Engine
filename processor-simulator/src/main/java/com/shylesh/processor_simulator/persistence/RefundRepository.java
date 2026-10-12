package com.shylesh.processor_simulator.persistence;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface RefundRepository extends JpaRepository<Refund, String> {

    /** Refunds created in the period, from the cursor on, by (createdAt, id): a settlement report page. */
    @Query("""
            SELECT r FROM Refund r
            WHERE r.createdAt >= :afterTime AND r.createdAt < :to
              AND (r.createdAt > :afterTime OR r.id > :afterId)
            ORDER BY r.createdAt, r.id
            """)
    List<Refund> pageCreatedIn(@Param("to") LocalDateTime to, @Param("afterTime") LocalDateTime afterTime,
                               @Param("afterId") String afterId, Limit limit);
}
