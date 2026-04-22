package com.thock.back.product.experiment;

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
public class ProductInboxExperimentListener {

    private final ProductStockService productStockService;
    private final ProductInboundEventIdempotencyKeyResolver idempotencyKeyResolver;
    private final ProductInboxExperimentRecorder recorder;

    @Value("${product.inbox-experiment.topic:market.order.stock.changed.experiment.inbox}")
    private String experimentTopic;

    @Value("${product.inbox-experiment.consumer-group:product-inbox-experiment}")
    private String consumerGroup;

    @KafkaListener(
            topics = "${product.inbox-experiment.topic:market.order.stock.changed.experiment.inbox}",
            groupId = "${product.inbox-experiment.consumer-group:product-inbox-experiment}",
            containerFactory = "productKafkaListenerContainerFactory"
    )
    public void handle(
            MarketOrderStockChangedEvent event,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(value = KafkaHeaders.RECEIVED_KEY, required = false) String messageKey
    ) {
        String runId = ProductInboxExperimentRecorder.extractRunId(event.orderNumber());
        if (runId == null) {
            log.debug("Inbox experiment event ignored. orderNumber={}", event.orderNumber());
            return;
        }

        long handledAtMillis = System.currentTimeMillis();
        String idempotencyKey = idempotencyKeyResolver.stockChanged(event);

        try {
            boolean processed = productStockService.handleKafka(
                    event,
                    experimentTopic,
                    idempotencyKey,
                    consumerGroup
            );

            if (!processed) {
                recorder.recordDuplicate(runId, handledAtMillis);
                return;
            }

            recorder.recordProcessed(runId, handledAtMillis);
        } catch (Exception e) {
            recorder.recordFailure(runId, handledAtMillis);
            log.error(
                    "Inbox experiment event failed. runId={}, orderNumber={}, partition={}, key={}",
                    runId,
                    event.orderNumber(),
                    partition,
                    messageKey,
                    e
            );
            throw e;
        }
    }
}
