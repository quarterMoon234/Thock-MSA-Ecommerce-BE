package com.thock.back.settlement.settlement.out;

import com.thock.back.settlement.settlement.domain.DailySettlement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface DailySettlementRepository extends JpaRepository<DailySettlement, Long> {
    List<DailySettlement> findBySellerIdAndTargetDateBetween(
            Long sellerId, LocalDate startDate, LocalDate endDate
    );
    List<DailySettlement> findAllByTargetDateBetween(LocalDate startDate, LocalDate endDate);


}
