package com.thock.back.product.messaging.publisher;

import com.thock.back.global.kafka.KafkaTopics;
import com.thock.back.shared.product.event.ProductEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "product.event", name = "publish-mode", havingValue = "direct")
public class ProductDirectKafkaEventPublisher implements ProductEventPublisher {

    private final @Qualifier("productKafkaTemplate") KafkaTemplate<String, Object> productKafkaTemplate;
    private final @Qualifier("productDirectPublishExecutor") TaskExecutor productDirectPublishExecutor;

    @Value("${product.event.direct.async-after-commit-enabled:false}")
    private boolean asyncAfterCommitEnabled;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(ProductEvent event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatchAfterCommit(event);
                }
            });
            return;
        }

        dispatchAfterCommit(event);
    }

    private void dispatchAfterCommit(ProductEvent event) {
        if (asyncAfterCommitEnabled) {
            productDirectPublishExecutor.execute(() -> sendSafely(event));
            return;
        }

        sendSafely(event);
    }

    private void sendSafely(ProductEvent event) {
        try {
            send(event);
        } catch (Exception e) {
            String eventKey = String.valueOf(event.productId());
            log.error("Failed to publish product event directly before send completion. key={}, error={}",
                    eventKey, e.getMessage(), e);
        }
    }

    private void send(ProductEvent event) {
        String eventKey = String.valueOf(event.productId());

        productKafkaTemplate.send(KafkaTopics.PRODUCT_CHANGED, eventKey, event)
                .whenComplete((result, throwable) -> {
                    if (throwable != null) {
                        log.error("Failed to publish product event directly. key={}, error={}", eventKey, throwable.getMessage());
                    }
                });
    }
}
