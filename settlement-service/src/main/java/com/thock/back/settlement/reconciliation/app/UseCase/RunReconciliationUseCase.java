package com.thock.back.settlement.reconciliation.app.UseCase;

import com.thock.back.settlement.reconciliation.domain.PgSalesRaw;
import com.thock.back.settlement.reconciliation.domain.SalesLog;
import com.thock.back.settlement.reconciliation.domain.VerificationResult;
import com.thock.back.settlement.reconciliation.domain.enums.ReconciliationStatus;
import com.thock.back.settlement.reconciliation.out.PgDataRepository;
import com.thock.back.settlement.reconciliation.out.SalesLogRepository;
import com.thock.back.settlement.reconciliation.out.VerificationResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class RunReconciliationUseCase {
    private final SalesLogRepository salesLogRepository;
    private final PgDataRepository pgDataRepository;
    private final VerificationResultRepository verificationResultRepository;

    //TODO: 3-2 대사 실패 시 슬랙 알림 추가

    // 주문서 데이터, PG 데이터를 각각 꺼내와서 비교 후 대사 완료 되었을 때 MATCH로 저장, 실패 했을 땐 MISMATCH로 저장
    // 만약 대사가 완료 됐다면 SaleLog의 컬럼도 변경해줘야 하는지?

    @Transactional
    public void execute(LocalDate date){
        log.info("=========[대사 시작] 기준일: {} =========", date);
        // 1. PG 데이터에서 해당 일자 전부 가져오기
        LocalDateTime startOfDay = date.atStartOfDay(); // xxxx-xx-xx 00:00:00
        LocalDateTime endOfDay = date.atTime(LocalTime.MAX);// xxxx-xx-xx- 23:59:59.999
        List<PgSalesRaw> pgList = pgDataRepository.findAllByTransactedAtBetween(startOfDay, endOfDay);

        log.info("조회된 PG 데이터 건수: {}건", pgList.size());

        int successCount = 0;
        int failCount = 0; // 대사 실패한 건의 횟수 기록
        int skipCount = 0; // 이미 처리된 건은 스킵해야되니, 그 횟수를 기록

        // 2. PG데이터의 주문 번호와 주문서의 주문번호 중 같은 것만 찾는다.
        for(PgSalesRaw pgSale: pgList){
            SalesLog saleLog = salesLogRepository.findByOrderNo(pgSale.getMerchantUid()).orElse(null);

            // 2-1. 일치하는 주문서가 없을 경우 (주문서 누락)
            if(saleLog == null){
                log.info("대사 실패: PG에는 존재하나 주문서에 없음. pgKey={}, orderNo={}",
                        pgSale.getPgKey(), pgSale.getMerchantUid());

                saveVerificationResult(pgSale, "주문서 누락", ReconciliationStatus.MISMATCH);
                failCount++;
                continue;
            }

            // 2-2. 이미 대사가 진행된 주문서라면 (중복 방지)
            if(saleLog.getReconciliationStatus() == ReconciliationStatus.MATCH){
                skipCount++;
                continue;
            }

            // 3. 주문번호가 일치 할 경우에
            Long pgAmount = pgSale.getPaymentAmount(); // PG 기준 결제금액
            Long logAmount = saleLog.getPaymentAmount(); // 주문서 기준 결제금액

            //  3-1. 일치한다면 SalesLog.reconciliationStatus = MATCH
            if(pgAmount.equals(logAmount)){
                saleLog.matchReconciliation();
                successCount++;
                log.info("대사 성공: 주문번호={}", pgSale.getMerchantUid());
            }
            // 3-2.일치하지 않는 부분이 있다면 SalesLog.reconciliationStatus = MISMATCH 후 VerificationResult에 기록
            else{
                log.info("대사 실패: 금액 불일치. PG={}, 주문서={})", pgSale.getPaymentAmount(), saleLog.getPaymentAmount());
                saleLog.mismatchReconciliation();
                long diff = logAmount - pgAmount;
                String reason = String.format("금액 불일치 (PG: %d, 내부: %d, 차액: %d)", pgAmount, logAmount, diff);
                saveVerificationResult(pgSale, reason, ReconciliationStatus.MISMATCH);
                failCount++;
            }
        }

        log.info("=========[대사 시작] 총: {}, 성공: {}, 실패: {}, 스킵: {}", pgList.size(), successCount, failCount, skipCount);
    }

    private void saveVerificationResult(PgSalesRaw pgSale, String reason, ReconciliationStatus status) {
        VerificationResult result = VerificationResult.builder()
                .baseDate(pgSale.getTransactedAt().toLocalDate()) // 대사 기준일
                .pgKey(pgSale.getPgKey())
                .orderNo(pgSale.getMerchantUid())
                .diffAmount(0L) // 누락인 경우 0
                .reconciliationStatus(status)
                .errorMessage(reason)
                .build();
        verificationResultRepository.save(result);
    }
}
