package com.thock.back.global.kafka;


import com.thock.back.shared.market.event.*;
import com.thock.back.shared.member.event.MemberJoinedEvent;
import com.thock.back.shared.member.event.MemberModifiedEvent;
import com.thock.back.shared.payment.event.PaymentCompletedEvent;
import com.thock.back.shared.payment.event.PaymentRefundCompletedEvent;
import com.thock.back.shared.product.event.ProductEvent;
import com.thock.back.shared.settlement.event.SettlementCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaEventPublisher {
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publish(Object event) {
        String topic = resolveTopicName(event);

        if (topic == null) {
            log.debug("No Kafka topic for event: {}", event.getClass().getSimpleName());
            return;
        }

        String messageKey = resolveMessageKey(event);

        kafkaTemplate.send(topic, messageKey, event);
        log.info("Published event to Kafka topic [{}]: type={}, key={}",
                topic, event.getClass().getSimpleName(), messageKey);
    }

    private String resolveTopicName(Object event) {
        if (event instanceof MemberJoinedEvent) {
            return KafkaTopics.MEMBER_JOINED;
        } else if (event instanceof MemberModifiedEvent) {
            return KafkaTopics.MEMBER_MODIFIED;
        } else if (event instanceof ProductEvent) {
            return KafkaTopics.PRODUCT_CHANGED;
        } else if (event instanceof MarketOrderPaymentRequestedEvent) {
            return KafkaTopics.MARKET_ORDER_PAYMENT_REQUESTED;
        } else if (event instanceof MarketOrderPaymentCompletedEvent) {
            return KafkaTopics.MARKET_ORDER_PAYMENT_COMPLETED;
        } else if (event instanceof MarketOrderPaymentRequestCanceledEvent) {
            return KafkaTopics.MARKET_ORDER_PAYMENT_REQUEST_CANCELED;
        } else if (event instanceof SettlementCompletedEvent) {
            return KafkaTopics.SETTLEMENT_COMPLETED;
        } else if (event instanceof PaymentRefundCompletedEvent) {
            return KafkaTopics.PAYMENT_REFUND_COMPLETED;
        } else if (event instanceof MarketOrderBeforePaymentCanceledEvent) {
            return KafkaTopics.MARKET_ORDER_BEFORE_PAYMENT_REQUEST_CANCELED;
        } else if (event instanceof MarketOrderDeletedEvent) {
            return KafkaTopics.MARKET_ORDER_DELETED;
        } else if (event instanceof MarketOrderSettlementEvent) {
            return KafkaTopics.MARKET_ORDER_SETTLEMENT;
        } else if (event instanceof MarketOrderStockChangedEvent) {
            return KafkaTopics.MARKET_ORDER_STOCK_CHANGED;
        } else if (event instanceof PaymentCompletedEvent) {
            return KafkaTopics.PAYMENT_COMPLETED;
        }

        return null;
    }

    private String resolveMessageKey(Object event) {
        if (event instanceof MemberJoinedEvent e) {
            return String.valueOf(e.member().id());
        } else if (event instanceof MemberModifiedEvent e) {
            return String.valueOf(e.member().id());
        } else if (event instanceof ProductEvent e) {
            return String.valueOf(e.productId());
        } else if (event instanceof MarketOrderPaymentRequestedEvent e) {
            return e.order().orderNumber();
        } else if (event instanceof MarketOrderPaymentCompletedEvent e) {
            return e.order().orderNumber();
        } else if (event instanceof MarketOrderPaymentRequestCanceledEvent e) {
            return e.dto().orderId();
        } else if (event instanceof SettlementCompletedEvent e) {
            return String.valueOf(e.memberID());
        } else if (event instanceof PaymentRefundCompletedEvent e) {
            return e.dto().orderId();
        } else if (event instanceof MarketOrderBeforePaymentCanceledEvent e) {
            return e.dto().orderId();
        } else if (event instanceof MarketOrderDeletedEvent e) {
            return e.dto().orderNumber();
        } else if (event instanceof MarketOrderSettlementEvent e) {
            return e.items().isEmpty() ? null : e.items().get(0).orderNo();
        } else if (event instanceof MarketOrderStockChangedEvent e) {
            return e.orderNumber();
        } else if (event instanceof PaymentCompletedEvent e) {
            return e.payment().orderId();
        }

        return null;
    }
}
