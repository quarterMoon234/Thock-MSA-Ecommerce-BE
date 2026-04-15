package com.thock.back.product.experiment;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@Profile("experiment")
public class ProductInboxExperimentTopicConfig {

    @Value("${product.inbox-experiment.topic:market.order.stock.changed.experiment.inbox}")
    private String topicName;

    @Value("${product.inbox-experiment.replicas:1}")
    private int replicas;

    @Bean
    public NewTopic productInboxExperimentTopic() {
        return TopicBuilder.name(topicName)
                .partitions(1)
                .replicas(replicas)
                .build();
    }
}
