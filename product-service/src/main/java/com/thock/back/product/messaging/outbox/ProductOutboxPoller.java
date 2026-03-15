package com.thock.back.product.messaging.outbox;

import com.thock.back.product.monitoring.ProductOutboxPublishMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "product.event", name = "publish-mode", havingValue = "outbox", matchIfMissing = true)
public class ProductOutboxPoller {

    private final ProductOutboxEventRepository productOutboxEventRepository;
    private final @Qualifier("productOutboxKafkaTemplate") KafkaTemplate<String, String> productOutboxKafkaTemplate;
    private ProductOutboxPublishMetrics productOutboxPublishMetrics;

    @Value("${product.outbox.enabled:true}")
    private boolean outboxEnabled = true;

    @Value("${product.outbox.poller.after-send-delay-ms:0}")
    private long afterSendDelayMs = 0L;

    @Scheduled(fixedDelayString = "${product.outbox.poller.interval-ms:3000}")
    @Transactional
    public void pollAndPublish() {
        if (!outboxEnabled) {
            return;
        }

        List<ProductOutboxEvent> events = productOutboxEventRepository.findTop100ByStatusOrderByCreatedAtAsc(ProductOutboxStatus.PENDING);

        for (ProductOutboxEvent event : events) {
            try {
                ProducerRecord<String, String> record = new ProducerRecord<>(event.getTopic(), event.getEventKey(), event.getPayload());
                record.headers().add("__TypeId__", event.getEventType().getBytes(StandardCharsets.UTF_8));

                productOutboxKafkaTemplate.send(record).get();
                applyAfterSendDelayIfConfigured(event.getId());
                event.markAsSent();
                recordSuccess();
            } catch (Exception e) {
                recordFailure();
                log.error("Failed to publish product outbox event. id={}, error={}", event.getId(), e.getMessage());
            }
        }
    }

    @Autowired(required = false)
    void setProductOutboxPublishMetrics(ProductOutboxPublishMetrics productOutboxPublishMetrics) {
        this.productOutboxPublishMetrics = productOutboxPublishMetrics;
    }

    private void applyAfterSendDelayIfConfigured(Long eventId) throws InterruptedException {
        if (afterSendDelayMs <= 0) {
            return;
        }

        log.info("Delaying outbox SENT update for experiment. id={}, delayMs={}", eventId, afterSendDelayMs);
        try {
            Thread.sleep(afterSendDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    private void recordSuccess() {
        if (productOutboxPublishMetrics != null) {
            productOutboxPublishMetrics.recordSuccess();
        }
    }

    private void recordFailure() {
        if (productOutboxPublishMetrics != null) {
            productOutboxPublishMetrics.recordFailure();
        }
    }
}
