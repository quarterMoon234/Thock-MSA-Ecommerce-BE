package com.thock.back.settlement.reconciliation.app.UseCase;

import com.thock.back.settlement.reconciliation.domain.PgSalesRaw;
import com.thock.back.settlement.reconciliation.domain.SalesLog;
import com.thock.back.settlement.reconciliation.domain.enums.ReconciliationStatus;
import com.thock.back.settlement.reconciliation.out.PgDataRepository;
import com.thock.back.settlement.reconciliation.out.SalesLogRepository;
import com.thock.back.settlement.reconciliation.out.VerificationResultRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RunReconciliationUseCaseTest {

    @InjectMocks
    RunReconciliationUseCase runReconciliationUseCase;

    @Mock
    SalesLogRepository salesLogRepository;
    @Mock
    PgDataRepository pgDataRepository;
    @Mock
    VerificationResultRepository verificationResultRepository;

    @Test
    @Order(1)
    @DisplayName("정상 대사: 성공하면 SalesLog.Reconciliation_status -> MATCH로 변경")
    void success_match() {
        // given
        LocalDate targetDate = LocalDate.of(2026, 2, 4);
        String orderNo = "ORD-001";
        Long amount = 10000L;

        // 테스트 PG사 데이터
        PgSalesRaw pgData = PgSalesRaw.builder()
                .pgKey("PG-KEY-111")
                .merchantUid(orderNo) // 주문번호 일치
                .paymentAmount(amount) // 금액 일치
                .transactedAt(LocalDateTime.now())
                .build();

        // 테스트 주문서 데이터
        SalesLog saleLog = SalesLog.builder()
                .orderNo(orderNo)
                .paymentAmount(amount)
                .reconciliationStatus(ReconciliationStatus.PENDING) // 아직 대사 전
                .build();

        // 1-3. 리포지토리 조회 후 바로 반환
        given(pgDataRepository.findAllByTransactedAtBetween(any(), any()))
                .willReturn(List.of(pgData));

        given(salesLogRepository.findByOrderNo(orderNo))
                .willReturn(Optional.of(saleLog));

        // when
        runReconciliationUseCase.execute(targetDate);

        // then
        assertThat(saleLog.getReconciliationStatus()).isEqualTo(ReconciliationStatus.MATCH);

        // 3-2. 성공한 경우는 verification_result에 로그 안쌓임
        verify(verificationResultRepository, times(0)).save(any());
    }

    @Test
    @Order(2)
    @DisplayName("금액 불일치 대사: verification_result 테이블에 저장되고, MISMATCH로 기록됨")
    void fail_amount_mismatch() {
        // given
        LocalDate targetDate = LocalDate.of(2026, 2, 4);
        String orderNo = "ORD-002";

        // PG값 10,000원, 주문서 5,000원
        PgSalesRaw pgData = PgSalesRaw.builder()
                .merchantUid(orderNo)
                .paymentAmount(10000L)
                .transactedAt(LocalDateTime.now())
                .build();

        SalesLog saleLog = SalesLog.builder()
                .orderNo(orderNo)
                .paymentAmount(5000L) // 금액 다름
                .reconciliationStatus(ReconciliationStatus.PENDING)
                .build();

        given(pgDataRepository.findAllByTransactedAtBetween(any(), any()))
                .willReturn(List.of(pgData));
        given(salesLogRepository.findByOrderNo(orderNo))
                .willReturn(Optional.of(saleLog));

        // when
        runReconciliationUseCase.execute(targetDate);

        // then
        // SalesLog.reconciliationStatus가 MISMATCH로 바뀌어야함
        assertThat(saleLog.getReconciliationStatus()).isEqualTo(ReconciliationStatus.MISMATCH);

        // 실패로그는 쌓여야함
        verify(verificationResultRepository, times(1)).save(any());
    }

    @Test
    @Order(3)
    @DisplayName("주문서 누락의 경우: 실패 로그만 쌓임")
    void fail_missing_order() {
        // 1. [Given]
        LocalDate targetDate = LocalDate.of(2026, 2, 4);
        String orderNo = "ORD-003";

        PgSalesRaw pgData = PgSalesRaw.builder()
                .merchantUid(orderNo)
                .paymentAmount(10000L)
                .transactedAt(LocalDateTime.now())
                .build();

        given(pgDataRepository.findAllByTransactedAtBetween(any(), any()))
                .willReturn(List.of(pgData));

        // 중요: 내 장부에서는 못 찾음 (Empty)
        given(salesLogRepository.findByOrderNo(orderNo))
                .willReturn(Optional.empty());

        // 2. [When]
        runReconciliationUseCase.execute(targetDate);

        // 3. [Then]
        // 결과 저장소가 호출되었는지 확인 (누락 로그 저장)
        verify(verificationResultRepository, times(1)).save(any());
    }

    @Test
    @Order(4)
    @DisplayName("⏭️ 중복 스킵: 이미 MATCH 된 건은 무시한다.")
    void skip_already_matched() {
        // 1. [Given]
        String orderNo = "ORD-004";

        PgSalesRaw pgData = PgSalesRaw.builder().merchantUid(orderNo).build();

        SalesLog saleLog = SalesLog.builder()
                .orderNo(orderNo)
                .reconciliationStatus(ReconciliationStatus.MATCH) // 이미 성공함!
                .build();

        given(pgDataRepository.findAllByTransactedAtBetween(any(), any())).willReturn(List.of(pgData));
        given(salesLogRepository.findByOrderNo(orderNo)).willReturn(Optional.of(saleLog));

        // 2. [When]
        runReconciliationUseCase.execute(LocalDate.now());

        // 3. [Then]
        // 아무 일도 안 일어나야 함 (성공 로직도, 실패 저장도 안 탐)
        // 즉, 상태 변경 로직 같은 게 실행 안 됐는지 간접 확인
        // (여기서는 verificationResultRepository.save가 안 불린 걸로 확인)
        verify(verificationResultRepository, times(0)).save(any());
    }
}