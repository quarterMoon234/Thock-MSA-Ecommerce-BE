package com.thock.back.product.in;

import com.thock.back.global.kafka.KafkaTopics;
import com.thock.back.product.app.ProductStockService;
import com.thock.back.product.in.idempotency.ProductInboundEventIdempotencyKeyResolver;
import com.thock.back.product.messaging.inbox.ProductInboxGuard;
import com.thock.back.shared.market.event.MarketOrderStockChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProductKafkaListener {

    private final ProductStockService productStockService;
    private final ObjectProvider<ProductInboxGuard> inboxGuardProvider;
    private final ProductInboundEventIdempotencyKeyResolver keyResolver;

    @Value("${product.kafka.consumer-group:product-service}")
    private String consumerGroup;

    // Kafka 토픽에서 재고 변경 이벤트를 수신하여 처리하는 메서드 -> DLQ 설정이 적용된 KafkaListener로, 이벤트 처리 중 예외가 발생하면 자동으로 DLQ로 이동
    @KafkaListener(
            topics = KafkaTopics.MARKET_ORDER_STOCK_CHANGED,
            groupId = "${product.kafka.consumer-group:product-service}",
            containerFactory = "productKafkaListenerContainerFactory"
    )
    @Transactional
    public void handle(
            MarketOrderStockChangedEvent event,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition, // 파티션 번호
            @Header(value = KafkaHeaders.RECEIVED_KEY, required = false) String messageKey // 메세지 키
    ) {
        String key = keyResolver.stockChanged(event);
        if (!shouldProcess(KafkaTopics.MARKET_ORDER_STOCK_CHANGED, key)) {
            log.info("Duplicate stock event ignored. orderNumber={}, eventType={}, partition={}, messageKey={}, thread={}",
                    event.orderNumber(),
                    event.eventType(),
                    partition,
                    messageKey,
                    Thread.currentThread().getName()); // 컨슈머도 사실은 하나의 스레드가 돌고 있는 것이므로 이벤트를 받은 컨슈머가 무슨 스레드인지 파악하기 위한 용도

            return;
        }

        try {
            productStockService.handle(event);
            log.info("Stock event processed. orderNumber={}, eventType={}, itemCount={}, partition={}, messageKey={}, thread={}",
                    event.orderNumber(),
                    event.eventType(),
                    event.items().size(),
                    partition,
                    messageKey,
                    Thread.currentThread().getName());
        } catch (Exception e) {
            log.error("Stock event failed. orderNumber={}, eventType={}, itemCount={}, partition={}, messageKey={}, thread={}",
                    event.orderNumber(),
                    event.eventType(),
                    event.items() == null ? 0 : event.items().size(),
                    partition,
                    messageKey,
                    Thread.currentThread().getName(),
                    e);
            throw e;
        }
    }

    // 이벤트의 중복 처리를 방지하기 위해 InboxGuard를 사용하여 이벤트 처리 여부를 결정하는 메서드
    private boolean shouldProcess(String topic, String idempotencyKey) {
        ProductInboxGuard inboxGuard = inboxGuardProvider.getIfAvailable();
        if (inboxGuard == null) {
            return true;
        }
        return inboxGuard.tryClaim(idempotencyKey, topic, consumerGroup);
    }
}
