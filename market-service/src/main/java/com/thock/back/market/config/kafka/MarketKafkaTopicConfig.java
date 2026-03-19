package com.thock.back.market.config.kafka;

import com.thock.back.global.kafka.KafkaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class MarketKafkaTopicConfig {

    @Value("${market.kafka.topics.stock-changed.partitions:1}")
    private int stockChangedPartitions;

    @Value("${market.kafka.topics.stock-changed.replicas:1}")
    private int stockChangedReplicas;

    @Bean
    public NewTopic marketOrderStockChangedTopic() {
        return TopicBuilder.name(KafkaTopics.MARKET_ORDER_STOCK_CHANGED)
                .partitions(stockChangedPartitions)
                .replicas(stockChangedReplicas)
                .build();
    }
}
