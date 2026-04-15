package com.thock.back.product.experiment;

import com.thock.back.global.kafka.KafkaTopics;
import com.thock.back.product.app.ProductStockService;
import com.thock.back.product.in.idempotency.ProductInboundEventIdempotencyKeyResolver;
import com.thock.back.shared.market.event.MarketOrderStockChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("experiment")
@RequiredArgsConstructor
public class ProductPartitionExperimentListener {

    private final ProductStockService productStockService;
    private final ProductInboundEventIdempotencyKeyResolver keyResolver;
    private final ProductPartitionExperimentRecorder productPartitionExperimentRecorder;

    @Value("${product.kafka.consumer-group:product-service}")
    private String consumerGroup;

    @Value("${product.partition-experiment.topic:market.order.stock.changed.experiment.single}")
    private String experimentTopic;

    @KafkaListener(
            topics = "${product.partition-experiment.topic:market.order.stock.changed.experiment.single}",
            groupId = "${product.kafka.consumer-group:product-service}",
            containerFactory = "productKafkaListenerContainerFactory"
    )
    public void handle(
            MarketOrderStockChangedEvent event,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(value = KafkaHeaders.RECEIVED_KEY, required = false) String messageKey
    ) {
        String runId = ProductPartitionExperimentRecorder.extractRunId(event.orderNumber());
        if (runId == null) {
            log.debug("Partition experiment event ignored. orderNumber={}", event.orderNumber());
            return;
        }

        String key = keyResolver.stockChanged(event);

        try {
            boolean processed = productStockService.handleKafka(
                    event,
                    experimentTopic,
                    key,
                    consumerGroup
            );

            if (!processed) {
                productPartitionExperimentRecorder.recordDuplicate(runId);
                return;
            }

            productPartitionExperimentRecorder.recordProcessed(
                    runId,
                    event.orderNumber(),
                    event.eventType(),
                    partition,
                    Thread.currentThread().getName(),
                    System.currentTimeMillis()
            );
        } catch (Exception e) {
            productPartitionExperimentRecorder.recordFailure(runId);
            log.error("Partition experiment event failed. runId={}, orderNumber={}, partition={}, key={}",
                    runId, event.orderNumber(), partition, messageKey, e);
            throw e;
        }
    }
}
