package com.thock.back.product.messaging.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;

public interface ProductOutboxEventRepository extends JpaRepository<ProductOutboxEvent, Long> {

    // PENDING 상태이면서 nextAttemptAt이 현재 시간 이전인 이벤트 오래된 순으로 100개 조회 (재시도 대상)
    @Query("""
            SELECT e
            FROM ProductOutboxEvent e
            WHERE e.status = :status
            AND (e.nextAttemptAt IS NULL OR e.nextAttemptAt <= :now)
            ORDER BY e.createdAt ASC
            """)
    List<ProductOutboxEvent> findRetryableByStatus(
            ProductOutboxStatus status,
            LocalDateTime now,
            Pageable pageable
    );

    // SENT 상태이면서 sentAt이 cutoff 이전인 이벤트 오래된 순으로 100개 조회 (정리 대상)
    @Query("""
            SELECT e.id
            FROM ProductOutboxEvent e
            WHERE e.status = :status
            AND e.sentAt IS NOT NULL 
            AND e.sentAt < :cutoff
            ORDER BY e.sentAt ASC
            """)
    List<Long> findCleanupTargetIds(
            ProductOutboxStatus status,
            LocalDateTime cutoff,
            Pageable pageable
    );

    @Query("""
            SELECT MIN(e.createdAt)
            FROM ProductOutboxEvent e
            WHERE e.status = :status
            """)
    LocalDateTime findOldestCreatedAtByStatus(ProductOutboxStatus status);

    long countByStatus(ProductOutboxStatus status);
}
