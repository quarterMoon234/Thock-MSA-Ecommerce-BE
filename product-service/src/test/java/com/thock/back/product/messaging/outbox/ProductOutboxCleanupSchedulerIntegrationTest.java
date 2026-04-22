package com.thock.back.product.messaging.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thock.back.global.kafka.KafkaTopics;
import com.thock.back.product.ProductServiceApplication;
import com.thock.back.shared.product.event.ProductEvent;
import com.thock.back.shared.product.event.ProductEventType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = ProductServiceApplication.class,
        properties = {
                "product.event.publish-mode=outbox",
                "product.outbox.enabled=true",
                "product.inbox.enabled=false",
                "spring.kafka.listener.auto-startup=false"
        }
)
@ActiveProfiles("test")
class ProductOutboxCleanupSchedulerIntegrationTest {

    @Autowired
    private ProductOutboxEventRepository productOutboxEventRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @AfterEach
    void tearDown() {
        productOutboxEventRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("cleanup removes only old SENT events")
    void cleanupSentEvents_whenOldSentExists_deletesOnlyOldSentEvents() throws Exception {
        ProductOutboxEvent oldSentEvent = productOutboxEventRepository.save(createEvent("11"));
        oldSentEvent.markAsSent(LocalDateTime.now().minusDays(10));
        oldSentEvent = productOutboxEventRepository.save(oldSentEvent);

        ProductOutboxEvent recentSentEvent = productOutboxEventRepository.save(createEvent("12"));
        recentSentEvent.markAsSent(LocalDateTime.now().minusDays(1));
        recentSentEvent = productOutboxEventRepository.save(recentSentEvent);

        ProductOutboxEvent failedEvent = productOutboxEventRepository.save(createEvent("13"));
        failedEvent.markAsFailed(10, "permanent failure");
        failedEvent = productOutboxEventRepository.save(failedEvent);

        ProductOutboxCleanupScheduler cleanupScheduler =
                new ProductOutboxCleanupScheduler(productOutboxEventRepository);
        ReflectionTestUtils.setField(cleanupScheduler, "cleanupEnabled", true);
        ReflectionTestUtils.setField(cleanupScheduler, "outboxEnabled", true);
        ReflectionTestUtils.setField(cleanupScheduler, "retentionDays", 7L);
        ReflectionTestUtils.setField(cleanupScheduler, "batchSize", 100);

        transactionTemplate.executeWithoutResult(status -> cleanupScheduler.cleanupSentEvents());

        assertThat(productOutboxEventRepository.findById(oldSentEvent.getId())).isEmpty();
        assertThat(productOutboxEventRepository.findById(recentSentEvent.getId())).isPresent();
        assertThat(productOutboxEventRepository.findById(failedEvent.getId())).isPresent();
    }

    private ProductOutboxEvent createEvent(String eventKey) throws Exception {
        ProductEvent event = ProductEvent.builder()
                .productId(Long.parseLong(eventKey))
                .sellerId(999L)
                .name("cleanup-test-" + eventKey)
                .price(100_000L)
                .salePrice(90_000L)
                .stock(3)
                .imageUrl("https://image.example/" + eventKey + ".png")
                .productState("ON_SALE")
                .eventType(ProductEventType.UPDATE)
                .build();

        return ProductOutboxEvent.create(
                KafkaTopics.PRODUCT_CHANGED,
                ProductEvent.class.getName(),
                eventKey,
                objectMapper.writeValueAsString(event)
        );
    }
}
