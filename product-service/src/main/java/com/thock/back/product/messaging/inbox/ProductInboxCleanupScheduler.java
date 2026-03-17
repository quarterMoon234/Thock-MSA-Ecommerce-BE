package com.thock.back.product.messaging.inbox;

import com.thock.back.product.monitoring.ProductInboxProcessMetrics;
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
@ConditionalOnProperty(prefix = "product.inbox", name = "enabled", havingValue = "true")
public class ProductInboxCleanupScheduler {

    private final ProductInboxEventRepository productInboxEventRepository;
    private ProductInboxProcessMetrics productInboxProcessMetrics;

    @Value("${product.inbox.cleanup.enabled:true}")
    private boolean cleanupEnabled = true;

    @Value("${product.inbox.cleanup.retention-days:7}")
    private long retentionDays = 7L;

    @Value("${product.inbox.cleanup.batch-size:500}")
    private int batchSize = 500;

    @Scheduled(fixedDelayString = "${product.inbox.cleanup.interval-ms:3600000}")
    @Transactional
    public void cleanup() {
        if (!cleanupEnabled) {
            return;
        }

        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);

        // 삭제할 ID 가져오기
        List<Long> targetIds = productInboxEventRepository.findCleanupTargetIds(
                cutoff,
                PageRequest.of(0, batchSize)
        );

        if (targetIds.isEmpty()) {
            return;
        }

        // Native SQL IN 으로 영속성 컨텍스트 생성 없이 한 방에 삭제
        productInboxEventRepository.deleteAllByIdInBatch(targetIds);
        recordCleanupDeleted(targetIds.size());

        log.info("Cleaned up product inbox events. deletedCount={}, cutoff={}, retentionDays={}",
                targetIds.size(), cutoff, retentionDays);

    }

    @Autowired(required = false)
    void setProductInboxProcessMetrics(ProductInboxProcessMetrics productInboxProcessMetrics) {
        this.productInboxProcessMetrics = productInboxProcessMetrics;
    }

    private void recordCleanupDeleted(long deletedCount) {
        if (productInboxProcessMetrics != null) {
            productInboxProcessMetrics.recordCleanupDeleted(deletedCount);
        }
    }
}
