package com.thock.back.product.messaging.outbox;

import com.thock.back.product.monitoring.ProductOutboxPublishMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "product.event", name = "publish-mode", havingValue = "outbox", matchIfMissing = true)
public class ProductOutboxCleanupScheduler {

    private final ProductOutboxEventRepository productOutboxEventRepository;
    private ProductOutboxPublishMetrics productOutboxPublishMetrics;

    @Value("${product.outbox.enabled:true}")
    private boolean outboxEnabled = true;

    @Value("${product.outbox.cleanup.enabled:true}")
    private boolean cleanupEnabled = true;

    @Value("${product.outbox.cleanup.retention-days:7}")
    private long retentionDays = 7L;

    @Value("${product.outbox.cleanup.batch-size:500}")
    private int batchSize = 500;

    @Scheduled(fixedDelayString = "${product.outbox.cleanup.interval-ms:3600000}") // 1시간마다 실행
    @Transactional
    public void cleanupSentEvents() {
        if (!outboxEnabled || !cleanupEnabled) {
            return;
        }

        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);

        List<Long> targetIds = productOutboxEventRepository.findCleanupTargetIds(
                ProductOutboxStatus.SENT,
                cutoff,
                PageRequest.of(0, batchSize)
        );

        if (targetIds.isEmpty()) {
            return;
        }

        productOutboxEventRepository.deleteAllByIdInBatch(targetIds);
        recordCleanupDeleted(targetIds.size());

        log.info("Cleaned up sent product outbox events. deletedCount={}, cutoff={}, retentionDays={}",
                targetIds.size(), cutoff, retentionDays);
    }

    @Autowired(required = false)
    void setProductOutboxPublishMetrics(ProductOutboxPublishMetrics productOutboxPublishMetrics) {
        this.productOutboxPublishMetrics = productOutboxPublishMetrics;
    }

    private void recordCleanupDeleted(long deletedCount) {
        if (productOutboxPublishMetrics != null) {
            productOutboxPublishMetrics.recordCleanupDeleted(deletedCount);
        }
    }
}
