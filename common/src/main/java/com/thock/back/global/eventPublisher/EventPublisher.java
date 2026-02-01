package com.thock.back.global.eventPublisher;

import com.thock.back.global.kafka.KafkaEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EventPublisher {
    private final ApplicationEventPublisher applicationEventPublisher;
    private final KafkaEventPublisher kafkaEventPublisher;

    public void publish(Object event) {
        // Local event for same-service listeners
        applicationEventPublisher.publishEvent(event);
        // Kafka event for cross-service communication
        kafkaEventPublisher.publish(event);
    }
}
