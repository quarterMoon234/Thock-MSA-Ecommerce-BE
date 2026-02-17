package com.thock.back.settlement.reconciliation.app;

import com.thock.back.settlement.reconciliation.app.UseCase.RunReconciliationUseCase;
import com.thock.back.settlement.reconciliation.app.UseCase.SavePgDataUseCase;
import com.thock.back.settlement.reconciliation.app.UseCase.SaveSalesLogUseCase;
import com.thock.back.settlement.reconciliation.domain.ProcessedEvent;
import com.thock.back.settlement.reconciliation.in.dto.ProcessedEventRepository;
import com.thock.back.settlement.reconciliation.in.dto.OrderItemMessageDto;
import com.thock.back.settlement.reconciliation.in.dto.PgSalesDto;
import com.thock.back.shared.settlement.dto.SettlementOrderItemDto;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReconciliationFacade {
    private final SaveSalesLogUseCase saveSalesLogUseCase;
    private final SavePgDataUseCase savePgDataUseCase;
    private final RunReconciliationUseCase runReconciliationUseCase;
    private final ProcessedEventRepository processedEventRepository;

//  --퍼싸드에선 pg 데이터 저장, 주문서 저장, 대사 진행 세가지의 메소드만 있으면 됨--

    // 1. 주문서를 받아와서 저장하는 로직(결제 완료의 주문서, 환불의 주문서)
    @Transactional
    public void receiveOrderItems(OrderItemMessageDto dto) {
        if (isDuplicateAndMark(dto, "API")) {
            return;
        }
        saveSalesLogUseCase.execute(dto);
    }

    @Transactional
    public void receiveSettlementItems(List<SettlementOrderItemDto> items) {
        if (items == null || items.isEmpty()) {
            return;
        }

        for (SettlementOrderItemDto item : items) {
            OrderItemMessageDto dto = toOrderItemMessageDto(item);
            if (isDuplicateAndMark(dto, "KAFKA")) {
                continue;
            }
            saveSalesLogUseCase.execute(dto);
        }
    }
    // 2. PG사의 주문서 저장하는 로직
    @Transactional
    public void receivePgData(List<PgSalesDto> dtos) {
        savePgDataUseCase.execute(dtos);
    }
    // 3. 1번과 2번의 데이터가 일치하는지 검증하는 로직
    @Transactional
    public void runReconciliation(LocalDate targetDate) {
        runReconciliationUseCase.execute(targetDate);
    }

    private OrderItemMessageDto toOrderItemMessageDto(SettlementOrderItemDto item) {
        return new OrderItemMessageDto(
                item.orderNo(),
                item.sellerId(),
                item.productId(),
                item.productName(),
                item.productQuantity(),
                item.productPrice(),
                item.paymentAmount(),
                item.eventType(),
                item.metadata(),
                item.snapshotAt()
        );
    }

    private boolean isDuplicateAndMark(OrderItemMessageDto dto, String source) {
        String idempotencyKey = makeIdempotencyKey(dto, source);
        try {
            processedEventRepository.save(ProcessedEvent.builder()
                    .idempotencyKey(idempotencyKey)
                    .source(source)
                    .eventType(dto.eventType())
                    .orderNo(dto.orderNo())
                    .build());
            return false;
        } catch (DataIntegrityViolationException e) {
            return true;
        }
    }

    private String makeIdempotencyKey(OrderItemMessageDto dto, String source) {
        String raw = source + "|" +
                dto.orderNo() + "|" +
                dto.productId() + "|" +
                dto.eventType() + "|" +
                dto.snapshotAt() + "|" +
                dto.paymentAmount() + "|" +
                dto.productQuantity();
        return sha256(raw);
    }

    private String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
