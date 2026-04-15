package com.thock.back.product.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class ProductDirectPublishAsyncConfig {

    @Bean("productDirectPublishExecutor")
    public TaskExecutor productDirectPublishExecutor(
            @Value("${product.event.direct.async.core-pool-size:8}") int corePoolSize,
            @Value("${product.event.direct.async.max-pool-size:16}") int maxPoolSize,
            @Value("${product.event.direct.async.queue-capacity:2000}") int queueCapacity
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("product-direct-publish-");
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
