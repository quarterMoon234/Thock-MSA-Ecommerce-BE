package com.thock.back.market.in;


import com.thock.back.global.inbox.InboxGuard;
import com.thock.back.global.kafka.KafkaTopics;
import com.thock.back.market.app.MarketFacade;
import com.thock.back.market.in.idempotency.MarketInboundEventIdempotencyKeyResolver;
import com.thock.back.shared.member.event.MemberJoinedEvent;
import com.thock.back.shared.member.event.MemberModifiedEvent;
import com.thock.back.shared.payment.event.PaymentCompletedEvent;
import com.thock.back.shared.payment.event.PaymentRefundCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class MarketKafkaListener {
    private static final String MARKET_CONSUMER_GROUP = "market-service";

    private final MarketFacade marketFacade;
    private final ObjectProvider<InboxGuard> inboxGuardProvider;
    private final MarketInboundEventIdempotencyKeyResolver keyResolver;

    @KafkaListener(topics = KafkaTopics.MEMBER_JOINED, groupId = "market-service")
    @Transactional
    public void handle(MemberJoinedEvent event){
        if (!shouldProcess(KafkaTopics.MEMBER_JOINED, keyResolver.memberJoined(event))) {
            return;
        }
        Long memberId = event.member().id();
        log.info("Received MemberJoinedEvent via Kafka: memberId={}", memberId);
        marketFacade.syncMember(event.member());
    }

    @KafkaListener(topics = KafkaTopics.MEMBER_MODIFIED, groupId = "market-service")
    @Transactional
    public void handle(MemberModifiedEvent event){
        if (!shouldProcess(KafkaTopics.MEMBER_MODIFIED, keyResolver.memberModified(event))) {
            return;
        }
        Long memberId = event.member().id();
        log.info("Received MemberModifiedEvent via Kafka: memberId={}", memberId);
        marketFacade.syncMember(event.member());
    }

    // 결제 완료가 되었을 때 payment 모듈에서 이벤트를 날리면 이벤트를 받아서 Order의 상태를 변경함
    @KafkaListener(topics = KafkaTopics.PAYMENT_COMPLETED, groupId = "market-service")
    @Transactional
    public void handle(PaymentCompletedEvent event){
        if (!shouldProcess(KafkaTopics.PAYMENT_COMPLETED, keyResolver.paymentCompleted(event))) {
            return;
        }
        String orderNumber = event.payment().orderId();
        log.info("Received PaymentCompletedEvent via Kafka: orderNumber={}", orderNumber);
        marketFacade.completeOrderPayment(orderNumber);
    }

    @KafkaListener(topics = KafkaTopics.PAYMENT_REFUND_COMPLETED, groupId = "market-service")
    @Transactional
    public void handle(PaymentRefundCompletedEvent event){
        if (!shouldProcess(
                KafkaTopics.PAYMENT_REFUND_COMPLETED,
                keyResolver.paymentRefundCompleted(event)
        )) {
            return;
        }
        Long memberId = event.dto().memberId();
        String orderNumber = event.dto().orderId();
        log.info("Received PaymentRefundCompletedEvent via Kafka: memberId = {}, orderNumber={}", memberId, orderNumber);
        marketFacade.completeRefund(orderNumber);
    }

    private boolean shouldProcess(String topic, String idempotencyKey) {
        InboxGuard inboxGuard = inboxGuardProvider.getIfAvailable();
        if (inboxGuard == null) {
            return true;
        }
        return inboxGuard.tryClaim(idempotencyKey, topic, MARKET_CONSUMER_GROUP);
    }

}
