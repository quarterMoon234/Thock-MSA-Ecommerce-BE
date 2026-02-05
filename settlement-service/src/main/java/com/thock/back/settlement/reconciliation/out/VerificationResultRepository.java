package com.thock.back.settlement.reconciliation.out;

import com.thock.back.settlement.reconciliation.domain.VerificationResult;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VerificationResultRepository extends JpaRepository<VerificationResult, Long> {
}
