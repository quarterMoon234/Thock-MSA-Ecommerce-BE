package com.thock.back.product.in;

import com.thock.back.global.kafka.KafkaTopics;
import com.thock.back.product.app.ProductStockService;
import com.thock.back.product.in.idempotency.ProductInboundEventIdempotencyKeyResolver;
import com.thock.back.shared.market.domain.StockEventType;
import com.thock.back.shared.market.dto.StockOrderItemDto;
import com.thock.back.shared.market.event.MarketOrderStockChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductKafkaListenerTest {

    private static final String CONSUMER_GROUP = "product-service";

    @Mock
    private ProductStockService productStockService;

    @Mock
    private ProductInboundEventIdempotencyKeyResolver keyResolver;

    private ProductKafkaListener listener;

    @BeforeEach
    void setUp() {
        listener = new ProductKafkaListener(productStockService, keyResolver);
        ReflectionTestUtils.setField(listener, "consumerGroup", CONSUMER_GROUP);
    }

    @Test
    @DisplayName("handle processes the stock event when kafka path returns processed")
    void handle_whenProcessed_logsSuccessPath() {
        MarketOrderStockChangedEvent event = stockChangedEvent("ORDER-1", StockEventType.RESERVE);

        when(keyResolver.stockChanged(event)).thenReturn("stock:ORDER-1:RESERVE");
        when(productStockService.handleKafka(
                event,
                KafkaTopics.MARKET_ORDER_STOCK_CHANGED,
                "stock:ORDER-1:RESERVE",
                CONSUMER_GROUP
        )).thenReturn(true);

        listener.handle(event, 0, "ORDER-1");

        verify(productStockService).handleKafka(
                event,
                KafkaTopics.MARKET_ORDER_STOCK_CHANGED,
                "stock:ORDER-1:RESERVE",
                CONSUMER_GROUP
        );
    }

    @Test
    @DisplayName("handle skips the stock event when kafka path reports duplicate")
    void handle_whenDuplicate_skipsEvent() {
        MarketOrderStockChangedEvent event = stockChangedEvent("ORDER-2", StockEventType.RELEASE);

        when(keyResolver.stockChanged(event)).thenReturn("stock:ORDER-2:RELEASE");
        when(productStockService.handleKafka(
                event,
                KafkaTopics.MARKET_ORDER_STOCK_CHANGED,
                "stock:ORDER-2:RELEASE",
                CONSUMER_GROUP
        )).thenReturn(false);

        listener.handle(event, 0, "ORDER-2");

        verify(productStockService).handleKafka(
                event,
                KafkaTopics.MARKET_ORDER_STOCK_CHANGED,
                "stock:ORDER-2:RELEASE",
                CONSUMER_GROUP
        );
    }

    @Test
    @DisplayName("handle propagates listener metadata to kafka stock handler")
    void handle_propagatesListenerMetadata() {
        MarketOrderStockChangedEvent event = stockChangedEvent("ORDER-3", StockEventType.COMMIT);

        when(keyResolver.stockChanged(event)).thenReturn("stock:ORDER-3:COMMIT");
        when(productStockService.handleKafka(
                event,
                KafkaTopics.MARKET_ORDER_STOCK_CHANGED,
                "stock:ORDER-3:COMMIT",
                CONSUMER_GROUP
        )).thenReturn(true);

        listener.handle(event, 0, "ORDER-3");

        verify(productStockService).handleKafka(
                event,
                KafkaTopics.MARKET_ORDER_STOCK_CHANGED,
                "stock:ORDER-3:COMMIT",
                CONSUMER_GROUP
        );
        verify(productStockService, never()).handle(event);
    }

    private MarketOrderStockChangedEvent stockChangedEvent(String orderNumber, StockEventType eventType) {
        return new MarketOrderStockChangedEvent(
                orderNumber,
                eventType,
                List.of(
                        new StockOrderItemDto(2L, 1),
                        new StockOrderItemDto(1L, 3)
                )
        );
    }
}
