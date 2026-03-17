package com.thock.back.product.messaging.inbox;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "product_inbox_event", uniqueConstraints = {
        @UniqueConstraint(
                name = "uk_product_inbox_event_topic_consumer_group_idempotency_key",
                columnNames = {"topic", "consumer_group", "idempotency_key"}
        )
}, indexes = {
        @Index(name = "idx_product_inbox_event_topic_consumer_group", columnList = "topic, consumer_group"),
        @Index(name = "idx_product_inbox_event_created_at", columnList = "created_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductInboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key" , nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "topic", nullable = false, length = 100)
    private String topic;

    @Column(name = "consumer_group", nullable = false, length = 100)
    private String consumerGroup;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public static ProductInboxEvent create(String idempotencyKey, String topic, String consumerGroup) {
        ProductInboxEvent event = new ProductInboxEvent();
        event.idempotencyKey = idempotencyKey;
        event.topic = topic;
        event.consumerGroup = consumerGroup;
        event.createdAt = LocalDateTime.now();
        return event;
    }
}
