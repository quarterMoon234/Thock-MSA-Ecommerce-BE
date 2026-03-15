package com.thock.back.product.messaging.publisher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thock.back.global.kafka.KafkaTopics;
import com.thock.back.product.ProductServiceApplication;
import com.thock.back.product.app.ProductCreateService;
import com.thock.back.product.domain.Category;
import com.thock.back.product.domain.command.ProductCreateCommand;
import com.thock.back.product.messaging.outbox.ProductOutboxEventRepository;
import com.thock.back.product.out.ProductRepository;
import com.thock.back.shared.member.domain.MemberRole;
import com.thock.back.shared.product.event.ProductEvent;
import com.thock.back.shared.product.event.ProductEventType;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = ProductServiceApplication.class,
        properties = {
                "product.event.publish-mode=direct",
                "product.outbox.enabled=false",
                "product.inbox.enabled=false",
                "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
                "spring.kafka.listener.auto-startup=false"
        }
)
@EmbeddedKafka(
        partitions = 1,
        topics = {KafkaTopics.PRODUCT_CHANGED}
)
@ActiveProfiles("test")
@DirtiesContext
class ProductDirectKafkaEventPublisherIntegrationTest {

    @Autowired
    private ProductCreateService productCreateService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductOutboxEventRepository productOutboxEventRepository;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @Autowired
    private ObjectMapper objectMapper;

    private Consumer<String, String> consumer;

    @AfterEach
    void tearDown() {
        if (consumer != null) {
            consumer.close();
        }
        productOutboxEventRepository.deleteAllInBatch();
        productRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("direct mode publishes product.changed without creating an outbox row")
    void createProduct_whenDirectMode_publishesWithoutOutboxRow() throws Exception {
        consumer = createConsumer();

        ProductCreateCommand command = new ProductCreateCommand(
                101L,
                MemberRole.SELLER,
                "Keychron Q1",
                230_000L,
                210_000L,
                15,
                Category.KEYBOARD,
                "mechanical keyboard",
                "https://image.example/q1.png",
                Map.of("switch", "red")
        );

        Long savedProductId = productCreateService.createProduct(command);

        ConsumerRecord<String, String> record =
                KafkaTestUtils.getSingleRecord(consumer, KafkaTopics.PRODUCT_CHANGED, Duration.ofSeconds(10));
        ProductEvent payload = objectMapper.readValue(record.value(), ProductEvent.class);

        assertThat(productRepository.existsById(savedProductId)).isTrue();
        assertThat(productOutboxEventRepository.findAll()).isEmpty();
        assertThat(record.key()).isEqualTo(String.valueOf(savedProductId));
        assertThat(payload.productId()).isEqualTo(savedProductId);
        assertThat(payload.sellerId()).isEqualTo(101L);
        assertThat(payload.eventType()).isEqualTo(ProductEventType.CREATE);
    }

    private Consumer<String, String> createConsumer() {
        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(
                "product-direct-publisher-test-" + UUID.randomUUID(),
                "false",
                embeddedKafkaBroker
        );
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        Consumer<String, String> kafkaConsumer = new org.springframework.kafka.core.DefaultKafkaConsumerFactory<>(
                consumerProps,
                new StringDeserializer(),
                new StringDeserializer()
        ).createConsumer();

        embeddedKafkaBroker.consumeFromAnEmbeddedTopic(kafkaConsumer, KafkaTopics.PRODUCT_CHANGED);
        return kafkaConsumer;
    }
}
