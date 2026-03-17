package com.thock.back.product.messaging.outbox;

public enum ProductOutboxStatus {
    PENDING, // 발행 대기 또는 재시도 대기
    SENT, // Kafka 발행 성공
    FAILED // 최대 재시도 초과 등으로 더 이상 자동 재시도하지 않음
}
