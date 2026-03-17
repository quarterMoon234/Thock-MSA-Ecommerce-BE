package com.thock.back.product.messaging.outbox;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "product_outbox_event", indexes = {
        @Index(name = "idx_product_outbox_event_status_created_at", columnList = "status, createdAt"),
        @Index(name = "idx_product_outbox_event_status_next_attempt_created_at", columnList = "status, nextAttemptAt, createdAt"),
        @Index(name = "idx_product_outbox_event_status_sent_at", columnList = "status, sentAt")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductOutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String topic;

    @Column(nullable = false, length = 200)
    private String eventType;

    @Column(nullable = false, length = 100)
    private String eventKey;

    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProductOutboxStatus status;

    @Column(nullable = false)
    private int retryCount;

    @Column
    private LocalDateTime nextAttemptAt;

    @Column(length = 1000)
    private String lastError;

    @Column
    private LocalDateTime sentAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public static ProductOutboxEvent create(String topic, String eventType, String eventKey, String payload) {
        ProductOutboxEvent event = new ProductOutboxEvent();
        event.topic = topic;
        event.eventType = eventType;
        event.eventKey = eventKey;
        event.payload = payload;
        event.status = ProductOutboxStatus.PENDING;
        event.retryCount = 0;
        event.createdAt = LocalDateTime.now();
        event.nextAttemptAt = event.createdAt;
        return event;
    }

    public void markAsSent() {
        markAsSent(LocalDateTime.now());
    }

    public void markAsSent(LocalDateTime sentAt) {
        this.status = ProductOutboxStatus.SENT;
        this.sentAt = sentAt;
        this.lastError = null;
    }

    public void scheduleRetry(int retryCount, LocalDateTime nextAttemptAt, String lastError) {
        this.status = ProductOutboxStatus.PENDING;
        this.retryCount = retryCount;
        this.nextAttemptAt = nextAttemptAt;
        this.lastError = truncateError(lastError);
    }

    public void markAsFailed(int retryCount, String lastError) {
        this.status = ProductOutboxStatus.FAILED;
        this.retryCount = retryCount;
        this.lastError = truncateError(lastError);
        this.nextAttemptAt = null;
    }

    private String truncateError(String errorMessage) {
        if (errorMessage == null) {
            return null;
        }
        if (errorMessage.length() <= 1000) {
            return errorMessage;
        }
        return errorMessage.substring(0, 1000);
    }
}
