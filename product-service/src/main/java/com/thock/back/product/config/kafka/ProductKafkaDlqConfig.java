package com.thock.back.product.config.kafka;

import com.thock.back.global.exception.CustomException;
import com.thock.back.global.kafka.KafkaTopics;
import com.thock.back.product.in.RetryableKafkaProcessingException;
import com.thock.back.product.monitoring.ProductDlqMetrics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.util.backoff.FixedBackOff;

/**
 이 설정이 완료되면, @KafkaListener(containerFactory = "productKafkaListenerContainerFactory")를 사용하는 모든 메서드는 여기서 정의한 DLQ 정책을 따르게 됩니다.
 **/


// 클래스명만 보면 DLQ 설정 클래스 같은데 사실 KafkaConfig에 DLQ를 얹은 느낌? 여기서 컨슈머 개수 설정 등도 다 다룬다. 사실 기본 밥에 DLQ는 조미료 첨가한 느낌
@Configuration
public class ProductKafkaDlqConfig {

    private final ConsumerFactory<String, Object> consumerFactory;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private ProductDlqMetrics productDlqMetrics;

    @Value("${product.dlq.retry.interval-ms:1000}")
    private long dlqRetryIntervalMs;

    @Value("${product.dlq.retry.max-attempts:2}")
    private long dlqRetryMaxAttempts;

    @Value("${product.kafka.listener.concurrency:1}")
    private int productKafkaListenerConcurrency;

    @Value("${spring.kafka.listener.auto-startup:true}")
    private boolean kafkaListenerAutoStartup;

    public ProductKafkaDlqConfig(
            @Qualifier("productConsumerFactory") ConsumerFactory<String, Object> consumerFactory,
            @Qualifier("productKafkaTemplate") KafkaTemplate<String, Object> kafkaTemplate
    ) {
        this.consumerFactory = consumerFactory;
        this.kafkaTemplate = kafkaTemplate;
    }

    // 메시지 배달 사고 처리기 (Recoverer)
    // 에러가 난 메시지를 잡아서 실제로 DLQ 토픽으로 **전송(Publish)**해주는 도구입니다.
    @Bean
    public DeadLetterPublishingRecoverer productDeadLetterPublishingRecoverer() {
        return new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, ex) -> new TopicPartition(KafkaTopics.MARKET_ORDER_STOCK_CHANGED_DLQ, 0) // 0번 파티션으로 고정해서 보낼거임 (파티션 키는 null로 보내면 됨)
        );
    }

    // 에러 핸들러 (Error Handler)
    // 메시지 처리가 실패했을 때 "몇 번이나 더 시도해볼지", **"포기하고 DLQ로 보낼지"**를 결정하는 두뇌 역할입니다.
    @Bean
    public CommonErrorHandler productKafkaErrorHandler(
            DeadLetterPublishingRecoverer productDeadLetterPublishingRecoverer
    ) {
        DefaultErrorHandler handler = new DefaultErrorHandler(
                productDeadLetterPublishingRecoverer, // 실패 시 앞에서 미리 만든 배달 사고 처리기 사용
                new FixedBackOff(dlqRetryIntervalMs, dlqRetryMaxAttempts)
        );

        // 기본 정책은 즉시 DLQ, 명시적으로 지정한 예외만 재시도합니다.
        handler.defaultFalse();
        handler.addRetryableExceptions(RetryableKafkaProcessingException.class);
        handler.addNotRetryableExceptions(
                CustomException.class,
                NullPointerException.class,
                IllegalArgumentException.class
        );

        // DLQ로 안전하게 보냈다면, Kafka에게 "이 메시지는 (비록 실패했지만) 처리가 끝났으니 다음 메시지를 달라"고 신호를 보냅니다.
        handler.setAckAfterHandle(true);

        handler.setRetryListeners(new RetryListener() {
            @Override
            public void failedDelivery(ConsumerRecord<?, ?> record, Exception ex, int deliveryAttempt) {
                if (deliveryAttempt > 1) {
                    recordRetryAttempt();
                }
            }

            @Override
            public void recovered(ConsumerRecord<?, ?> record, Exception ex) {
                recordDlqPublished();

                if (containsCustomException(ex)) {
                    recordNonRetryable();
                    return;
                }

                recordRetryExhausted();
            }
        });

        return handler;
    }

    // 리스너 팩토리 (Container Factory)
    // 마지막으로 위에서 설정한 에러 핸들러를 실제 메시지 리스너(@KafkaListener)에 연결해주는 작업대입니다.
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> productKafkaListenerContainerFactory(
            CommonErrorHandler productKafkaErrorHandler
    ) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();

        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(productKafkaListenerConcurrency);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.setCommonErrorHandler(productKafkaErrorHandler); // 에러 핸들러 장착!
        factory.setAutoStartup(kafkaListenerAutoStartup);

        return factory;
    }

    @Autowired(required = false)
    void setProductDlqMetrics(ProductDlqMetrics productDlqMetrics) {
        this.productDlqMetrics = productDlqMetrics;
    }

    private boolean containsCustomException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof CustomException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void recordRetryAttempt() {
        if (productDlqMetrics != null) {
            productDlqMetrics.recordRetryAttempt();
        }
    }

    private void recordDlqPublished() {
        if (productDlqMetrics != null) {
            productDlqMetrics.recordDlqPublished();
        }
    }

    private void recordNonRetryable() {
        if (productDlqMetrics != null) {
            productDlqMetrics.recordNonRetryable();
        }
    }

    private void recordRetryExhausted() {
        if (productDlqMetrics != null) {
            productDlqMetrics.recordRetryExhausted();
        }
    }

}
