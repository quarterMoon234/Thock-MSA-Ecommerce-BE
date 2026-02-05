package com.thock.back.settlement.reconciliation.app.UseCase;

import com.thock.back.settlement.reconciliation.domain.SalesLog;
import com.thock.back.settlement.reconciliation.domain.enums.OrderEventStatus;
import com.thock.back.settlement.reconciliation.in.dto.OrderItemMessageDto;
import com.thock.back.settlement.reconciliation.out.SalesLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SaveSalesLogUseCase {
    private final SalesLogRepository salesLogRepository;
    public void execute(OrderItemMessageDto dto){
        OrderEventStatus eventStatus = OrderEventStatus.from(dto.eventType());
        switch (eventStatus) {

            case PAYMENT_COMPLETED, REFUND_COMPLETED -> {
                salesLogRepository.save(dto.toEntity());
            }

            case PURCHASE_CONFIRMED -> {
                salesLogRepository.findByOrderNoAndTransactionType(
                        dto.orderNo(),
                        eventStatus.getTransactionType()
                ).ifPresent(SalesLog::readySettlement);
            }
        }
    }
}
