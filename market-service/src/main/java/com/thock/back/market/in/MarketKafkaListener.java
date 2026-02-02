package com.thock.back.market.in;


import com.thock.back.global.kafka.KafkaTopics;
import com.thock.back.market.app.MarketFacade;
import com.thock.back.shared.member.event.MemberJoinedEvent;
import com.thock.back.shared.member.event.MemberModifiedEvent;
import com.thock.back.shared.payment.event.PaymentCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class MarketKafkaListener {
    private final MarketFacade marketFacade;

    @KafkaListener(topics = KafkaTopics.MEMBER_JOINED, groupId = "market-service")
    @Transactional
    public void handle(MemberJoinedEvent event){
        log.info("Received MemberJoinedEvent via Kafka: memberId={}", event.member().id());
        marketFacade.syncMember(event.member());
    }

    @KafkaListener(topics = KafkaTopics.MEMBER_MODIFIED, groupId = "market-service")
    @Transactional
    public void handle(MemberModifiedEvent event){
        log.info("Received MemberModifiedEvent via Kafka: memberId={}", event.member().id());
        marketFacade.syncMember(event.member());
    }

//
//    @KafkaListener
//    @Transactional
//    public void handle(PaymentCompletedEvent event){
//        String orderId = event.getPayment().getOrderId();
//        marketFacade.completeOrderPayment(orderId);
//    }


}
