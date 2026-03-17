package com.thock.back.product.in;

import com.thock.back.global.kafka.KafkaTopics;
import com.thock.back.product.ProductServiceApplication;
import com.thock.back.product.domain.Category;
import com.thock.back.product.domain.entity.Product;
import com.thock.back.product.messaging.inbox.ProductInboxEventRepository;
import com.thock.back.product.out.ProductRepository;
import com.thock.back.shared.market.domain.StockEventType;
import com.thock.back.shared.market.dto.StockOrderItemDto;
import com.thock.back.shared.market.event.MarketOrderStockChangedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = ProductServiceApplication.class,
        properties = {
                "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
                "spring.kafka.listener.auto-startup=true",
                "spring.kafka.consumer.auto-offset-reset=earliest",
                "product.inbox.enabled=true",
                "product.inbox.cleanup.enabled=false"
        }
)
@EmbeddedKafka(
        partitions = 1,
        topics = {
                KafkaTopics.MARKET_ORDER_STOCK_CHANGED
        }
)
@ActiveProfiles("test")
@DirtiesContext
class ProductKafkaInboxIntegrationTest {

    @Autowired
    @Qualifier("productKafkaTemplate")
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductInboxEventRepository productInboxEventRepository;

    @Autowired
    private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @BeforeEach
    void setUp() {
        for (MessageListenerContainer container : kafkaListenerEndpointRegistry.getListenerContainers()) {
            ContainerTestUtils.waitForAssignment(container, embeddedKafkaBroker.getPartitionsPerTopic());
        }
    }

    @AfterEach
    void tearDown() {
        productInboxEventRepository.deleteAllInBatch();
        productRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("duplicate stock event is applied only once")
    void handle_whenSameEventPublishedTwice_appliesStockChangeOnce() throws Exception {
        Product product = productRepository.save(Product.builder()
                .sellerId(1L)
                .category(Category.KEYBOARD)
                .name("inbox-duplicate-test-product")
                .description("duplicate inbox test")
                .price(10000L)
                .salePrice(9000L)
                .stock(10)
                .imageUrl("https://example.com/product.png")
                .build());

        Long productId = product.getId();

        MarketOrderStockChangedEvent event = new MarketOrderStockChangedEvent(
                "ORDER-DUP-1",
                StockEventType.RESERVE,
                List.of(new StockOrderItemDto(productId, 2))
        );

        kafkaTemplate.send(KafkaTopics.MARKET_ORDER_STOCK_CHANGED, "ORDER-DUP-1", event)
                .get(5, TimeUnit.SECONDS);
        kafkaTemplate.send(KafkaTopics.MARKET_ORDER_STOCK_CHANGED, "ORDER-DUP-1", event)
                .get(5, TimeUnit.SECONDS);

        waitUntil(() -> {
            Product reloaded = productRepository.findById(productId).orElseThrow();
            return reloaded.getReservedStock() == 2
                    && reloaded.getStock() == 10
                    && productInboxEventRepository.count() == 1;
        }, 10_000L);

        Product reloaded = productRepository.findById(productId).orElseThrow();

        assertThat(reloaded.getReservedStock()).isEqualTo(2);
        assertThat(reloaded.getStock()).isEqualTo(10);
        assertThat(productInboxEventRepository.count()).isEqualTo(1);
    }

    private void waitUntil(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(200L);
        }

        throw new AssertionError("Condition was not met within " + timeoutMs + "ms");
    }
}
