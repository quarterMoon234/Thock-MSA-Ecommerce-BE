package com.thock.back.product.messaging.inbox;

import com.thock.back.product.ProductServiceApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = ProductServiceApplication.class,
        properties = {
                "product.inbox.enabled=true",
                "product.inbox.cleanup.enabled=true",
                "spring.kafka.listener.auto-startup=false"
        }
)
@ActiveProfiles("test")
class ProductInboxCleanupSchedulerIntegrationTest {

    @Autowired
    private ProductInboxCleanupScheduler productInboxCleanupScheduler;

    @Autowired
    private ProductInboxEventRepository productInboxEventRepository;

    @AfterEach
    void tearDown() {
        productInboxEventRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("cleanup deletes only inbox events older than retention")
    void cleanup_whenOldInboxEventsExist_deletesOnlyOldRows() {
        ReflectionTestUtils.setField(productInboxCleanupScheduler, "retentionDays", 7L);
        ReflectionTestUtils.setField(productInboxCleanupScheduler, "batchSize", 100);

        ProductInboxEvent oldEvent = saveInboxEvent(
                "old-order:reserve:1-2",
                "market.order.stock.changed",
                "product-service",
                LocalDateTime.now().minusDays(10)
        );
        ProductInboxEvent recentEvent = saveInboxEvent(
                "recent-order:reserve:1-2",
                "market.order.stock.changed",
                "product-service",
                LocalDateTime.now().minusDays(1)
        );

        productInboxCleanupScheduler.cleanup();

        assertThat(productInboxEventRepository.findById(oldEvent.getId())).isEmpty();
        assertThat(productInboxEventRepository.findById(recentEvent.getId())).isPresent();
    }

    private ProductInboxEvent saveInboxEvent(
            String idempotencyKey,
            String topic,
            String consumerGroup,
            LocalDateTime createdAt
    ) {
        ProductInboxEvent event = ProductInboxEvent.create(idempotencyKey, topic, consumerGroup);
        ReflectionTestUtils.setField(event, "createdAt", createdAt);
        return productInboxEventRepository.save(event);
    }
}
