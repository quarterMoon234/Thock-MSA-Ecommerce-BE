package com.thock.back.product.experiment;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@Profile("experiment")
public class ProductPartitionExperimentTopicConfig {

    @Value("${product.partition-experiment.single-topic.name:market.order.stock.changed.experiment.single}")
    private String singleTopicName;

    @Value("${product.partition-experiment.multi-topic.name:market.order.stock.changed.experiment.multi}")
    private String multiTopicName;

    @Value("${product.partition-experiment.multi-topic.partitions:3}")
    private int multiTopicPartitions;

    @Value("${product.partition-experiment.replicas:1}")
    private int replicas;

    @Bean
    public NewTopic productPartitionExperimentSingleTopic() {
        return TopicBuilder.name(singleTopicName)
                .partitions(1)
                .replicas(replicas)
                .build();
    }

    @Bean
    public NewTopic productPartitionExperimentMultiTopic() {
        return TopicBuilder.name(multiTopicName)
                .partitions(multiTopicPartitions)
                .replicas(replicas)
                .build();
    }
}
