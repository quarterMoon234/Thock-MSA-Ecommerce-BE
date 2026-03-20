package com.thock.back.market.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
@Slf4j
public class CircuitBreakerMonitoringConfig {

    @Bean
    public SmartInitializingSingleton circuitBreakerEventLogger(CircuitBreakerRegistry circuitBreakerRegistry) {
        Set<String> registeredCircuitBreakers = ConcurrentHashMap.newKeySet();

        return () -> {
            circuitBreakerRegistry.getAllCircuitBreakers()
                    .forEach(circuitBreaker -> registerEventLogger(circuitBreaker, registeredCircuitBreakers));

            circuitBreakerRegistry.getEventPublisher()
                    .onEntryAdded(event -> registerEventLogger(event.getAddedEntry(), registeredCircuitBreakers));
        };
    }

    private void registerEventLogger(CircuitBreaker circuitBreaker, Set<String> registeredCircuitBreakers) {
        if (!registeredCircuitBreakers.add(circuitBreaker.getName())) {
            return;
        }

        circuitBreaker.getEventPublisher()
                .onStateTransition(event -> {
                    CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();
                    log.warn("[CircuitBreaker] name={}, transition={}, failureRate={}, bufferedCalls={}, failedCalls={}, notPermittedCalls={}",
                            circuitBreaker.getName(),
                            event.getStateTransition(),
                            metrics.getFailureRate(),
                            metrics.getNumberOfBufferedCalls(),
                            metrics.getNumberOfFailedCalls(),
                            metrics.getNumberOfNotPermittedCalls());
                })
                .onCallNotPermitted(event -> log.warn(
                        "[CircuitBreaker] name={}, event=CALL_NOT_PERMITTED, notPermittedCalls={}",
                        circuitBreaker.getName(),
                        circuitBreaker.getMetrics().getNumberOfNotPermittedCalls()
                ))
                .onError(event -> log.warn(
                        "[CircuitBreaker] name={}, event=ERROR, elapsedMs={}, failureRate={}, throwable={}",
                        circuitBreaker.getName(),
                        event.getElapsedDuration().toMillis(),
                        circuitBreaker.getMetrics().getFailureRate(),
                        event.getThrowable().getClass().getSimpleName()
                ));
    }
}
