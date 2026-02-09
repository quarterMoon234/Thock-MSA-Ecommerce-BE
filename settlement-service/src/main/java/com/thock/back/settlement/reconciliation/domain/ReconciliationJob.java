package com.thock.back.settlement.reconciliation.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import com.github.f4b6a3.tsid.TsidCreator; // TSID 라이브러리
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor
@Table(name = "finance_reconciliation_job")
public class ReconciliationJob {

    @Id
    private Long id; // TSID (PK)

    @Column(nullable = false)
    private LocalDate baseDate; // 대사 기준일 (예: 2026-02-06)

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status; // RUNNING, SUCCESS, FAIL, WARNING

    private Integer totalCount;    // 총 비교 건수
    private Integer matchCount;    // 일치 건수
    private Integer mismatchCount; // 불일치 건수

    private LocalDateTime startedAt;  // 배치 시작 시간
    private LocalDateTime finishedAt; // 배치 종료 시간

    @Column(length = 1000)
    private String failReason; // 시스템 에러 등으로 배치가 터졌을 때 사유

    @PrePersist
    public void prePersist() {
        if (this.id == null) {
            this.id = TsidCreator.getTsid().toLong();
        }
        if (this.status == null) {
            this.status = JobStatus.RUNNING;
        }
        if (this.startedAt == null) {
            this.startedAt = LocalDateTime.now();
        }
    }

    // 배치 종료 시 업데이트를 위한 편의 메서드
    public void finish(int total, int match, int mismatch) {
        this.totalCount = total;
        this.matchCount = match;
        this.mismatchCount = mismatch;
        this.finishedAt = LocalDateTime.now();

        if (mismatch > 0) {
            this.status = JobStatus.WARNING; // 불일치가 있으면 경고
        } else {
            this.status = JobStatus.SUCCESS; // 완벽하면 성공
        }
    }

    public void fail(String reason) {
        this.status = JobStatus.FAIL;
        this.failReason = reason;
        this.finishedAt = LocalDateTime.now();
    }

    public enum JobStatus {
        RUNNING, SUCCESS, WARNING, FAIL
    }
}