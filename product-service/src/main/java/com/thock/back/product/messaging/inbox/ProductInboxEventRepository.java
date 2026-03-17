package com.thock.back.product.messaging.inbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ProductInboxEventRepository extends JpaRepository<ProductInboxEvent, Long> {

    // idempotencyKey + topic + consumerGroup -> DB 유니크 제약으로 검증
    @Modifying
    @Query(
            value = """
                    INSERT IGNORE INTO product_inbox_event
                    (idempotency_key, topic, consumer_group, created_at)
                    VALUES (:idempotencyKey, :topic, :consumerGroup, NOW(6))
                    """,
            nativeQuery = true
    )
    int claimIfAbsent(@Param("idempotencyKey") String idempotencyKey,
                      @Param("topic") String topic,
                      @Param("consumerGroup") String consumerGroup);

    // ID <pageable>개 오름차순으로 가져옴
    @Query("""
            SELECT e.id
            FROM ProductInboxEvent e
            WHERE e.createdAt < :cutoff
            ORDER BY e.createdAt ASC
            """)
    List<Long> findCleanupTargetIds(@Param("cutoff") LocalDateTime cutoff, Pageable pageable);

    @Query("""
            SELECT MIN(e.createdAt)
            FROM ProductInboxEvent e
            """)
    LocalDateTime findOldestCreatedAt();
}
