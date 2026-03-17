package com.thock.back.product.messaging.outbox;

import com.thock.back.product.monitoring.ProductOutboxPublishMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

/**
 Poller 작동 방식
 -> 스케쥴링 정책에 따라 Poller가 주기적으로 실행됨 (예: 3초마다)
 -> 트랜잭션 시작
 -> Outbox 테이블에서 PENDING 상태이면서 nextAttemptAt이 현재 시간 이전인 이벤트를 오래된 순으로 100개 조회
 -> 반복문으로 100개 이벤트 발행 시작
 -> 발행 성공 시 SENT 변경
 -> 발행 실패 시 카운트 1증가 + 재시도 시간 계산
 -> 이런식으로 반복문이 끝날때까지 돌면서 각 테이블을 성공/실패 처리 (영속성 컨텍스트에다 써 놓기만 함)
 -> 반복문 종료
 -> 트랜잭션 종료
 -> 100개 영속성 컨텍스트에 써 놓았던 내용들 모두 커밋
 -> Poller 종료
 -> 스케쥴링 정책으로 Poller가 재실행 되면 그때 또 100개 꺼내서 같은 방식으로 처리
 -> 이런식으로 반복되면 반복적으로 실패하는 이벤트는 결국 걸러짐
 **/
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "product.event", name = "publish-mode", havingValue = "outbox", matchIfMissing = true)
public class ProductOutboxPoller {

    private static final int BATCH_SIZE = 100;

    private final ProductOutboxEventRepository productOutboxEventRepository;
    private final @Qualifier("productOutboxKafkaTemplate")KafkaTemplate<String, String> productOutboxKafkaTemplate;

    private ProductOutboxPublishMetrics productOutboxPublishMetrics;

    @Value("${product.outbox.enabled:true}")
    private boolean outboxEnabled = true;

    @Value("${product.outbox.poller.after-send-delay-ms:0}")
    private long afterSendDelayMs = 0L;

    @Value("${product.outbox.max-retries:10}")
    private int maxRetries = 10;

    @Value("${product.outbox.retry-delay-ms:5000}")
    private long retryDelayMs = 5000L; // 1000ms = 1초

    @Value("${product.outbox.max-retry-delay-ms:300000}")
    private long maxRetryDelayMs = 300000L; // 300000ms = 300초

    @Scheduled(fixedDelayString = "${product.outbox.poller.interval-ms:3000}")
    @Transactional
    public void pollAndPublish() {
        if (!outboxEnabled) {
            return;
        }

        List<ProductOutboxEvent> events = productOutboxEventRepository.findRetryableByStatus(
                ProductOutboxStatus.PENDING,
                LocalDateTime.now(),
                PageRequest.of(0, BATCH_SIZE)
        );

        for (ProductOutboxEvent event : events) {
            publishSingleEvent(event);
        }
    }

    private void publishSingleEvent(ProductOutboxEvent event) {
        try {
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    event.getTopic(),
                    event.getEventKey(),
                    event.getPayload()
            );
            record.headers().add("__TypeId__", event.getEventType().getBytes(StandardCharsets.UTF_8));

            productOutboxKafkaTemplate.send(record).get();
            applyAfterSendDelayIfConfigured(event.getId());

            event.markAsSent(LocalDateTime.now());
            recordSuccess();

            log.info("Published product outbox event. id={}, topic={}, eventKey={}",
                    event.getId(), event.getTopic(), event.getEventKey());
        } catch (Exception e) {
            handlePublishFailure(event, e);
        }
    }

    private void handlePublishFailure(ProductOutboxEvent event, Exception e) {
        recordFailure();

        // 1. 실패 횟수를 1 증가시킵니다.
        int nextRetryCount = event.getRetryCount() + 1;
        String errorMessage = buildErrorMessage(e);

        // 2. 최대 재시도 횟수(예: 10번)에 도달했는지 확인합니다.
        if (nextRetryCount >= maxRetries) {
            event.markAsFailed(nextRetryCount, errorMessage);
            recordMovedToFailed();
            log.error("Product outbox event moved to FAILED. id={}, retryCount={}, error={}",
                    event.getId(), nextRetryCount, errorMessage);
            return;
        }

        // 3. 아직 기회가 남았다면, "다음번엔 언제 시도할지(nextAttemptAt)" 계산합니다.
        LocalDateTime nextAttemptAt = calculateNextAttemptAt(nextRetryCount);

        // 4. 이벤트 엔티티에 다음 시도 시간과 에러 메시지를 기록해둡니다.
        event.scheduleRetry(nextRetryCount, nextAttemptAt, errorMessage);
        recordRetryScheduled();

        log.warn("Failed to publish product outbox event. id={}, retryCount={}, nextAttemptAt={}, error={}",
                event.getId(), nextRetryCount, nextAttemptAt, errorMessage);
    }

    //
    @Autowired(required = false)
    void setProductOutboxPublishMetrics(ProductOutboxPublishMetrics productOutboxPublishMetrics) {
        this.productOutboxPublishMetrics = productOutboxPublishMetrics;
    }

    private void applyAfterSendDelayIfConfigured(Long eventId) throws InterruptedException {
        if (afterSendDelayMs <= 0) {
            return;
        }

        log.info("Delaying outbox SENT update for experiment. id={}, delayMs={}", eventId, afterSendDelayMs);
        try {
            Thread.sleep(afterSendDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    // calculateDelayMs() 에서 계산한 '대기 시간(ms)'을 '실제 시각(LocalDateTime)'으로 변환합니다.
    private LocalDateTime calculateNextAttemptAt(int retryCount) {
        long delayMs = calculateDelayMs(retryCount);
        return LocalDateTime.now().plusNanos(delayMs * 1_000_000);
    }

    /**
     이 코드의 하이라이트입니다. "몇 초 뒤에 다시 시도할까?"를 계산하는 부분입니다.

     왜 그냥 "무조건 5초 뒤에 다시 해"라고 하지 않고 복잡한 for 문을 돌릴까요?
     만약 카프카 서버가 과부하로 죽어있는데, 모든 이벤트가 5초마다 똑같이 재시도를 때린다면 카프카는 복구되자마자 다시 터져버릴 겁니다 (이를 Thundering Herd 문제라고 합니다).

     그래서 "실패할수록 재시도 간격을 2배씩 점점 늘려주는" 똑똑한 방식을 씁니다. 코드를 보면서 숫자를 대입해 볼게요.

     1번째 재시도 (retryCount=1): for 문 안 돎. 5초 뒤 재시도
     2번째 재시도 (retryCount=2): for 문 1번 돎 (5초 * 2). 10초 뒤 재시도
     3번째 재시도 (retryCount=3): for 문 2번 돎 (10초 * 2). 20초 뒤 재시도
     4번째 재시도 (retryCount=4): 40초 뒤...
     이렇게 늘어나다가 **5분(300초)**에 도달하면 그 이후부터는 계속 5분 간격으로만 재시도합니다.
     * */
    private long calculateDelayMs(int retryCount) {
        long delayMs = retryDelayMs; // 시작은 5초(5000ms)

        // 재시도 횟수만큼 반복하며 간격을 2배씩 뻥튀기합니다.
        for (int i = 1; i < retryCount; i++) {
            if (delayMs >= maxRetryDelayMs) {
                return maxRetryDelayMs; // 단, 최대 5분을 넘기진 않음
            }
            delayMs = Math.min(delayMs * 2, maxRetryDelayMs); // 2배 곱하기
        }
        return delayMs;
    }

    private String buildErrorMessage(Exception e) {
        String base = e.getClass().getSimpleName();
        if (e.getMessage() == null || e.getMessage().isBlank()) {
            return base;
        }
        return base + ": " + e.getMessage();
    }

    private void recordSuccess() {
        if (productOutboxPublishMetrics != null) {
            productOutboxPublishMetrics.recordSuccess();
        }
    }

    private void recordFailure() {
        if (productOutboxPublishMetrics != null) {
            productOutboxPublishMetrics.recordFailure();
        }
    }

    private void recordRetryScheduled() {
        if (productOutboxPublishMetrics != null) {
            productOutboxPublishMetrics.recordRetryScheduled();
        }
    }

    private void recordMovedToFailed() {
        if (productOutboxPublishMetrics != null) {
            productOutboxPublishMetrics.recordMovedToFailed();
        }
    }
}
