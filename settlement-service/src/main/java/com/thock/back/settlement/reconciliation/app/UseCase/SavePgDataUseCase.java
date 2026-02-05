package com.thock.back.settlement.reconciliation.app.UseCase;

import com.thock.back.settlement.reconciliation.domain.PgSalesRaw;
import com.thock.back.settlement.reconciliation.in.dto.PgSalesDto;
import com.thock.back.settlement.reconciliation.out.PgDataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SavePgDataUseCase {
    private final PgDataRepository pgDataRepository;

    @Transactional
    public void execute(List<PgSalesDto> dtos){
        List<PgSalesRaw> entities = dtos.stream()
                .map(PgSalesDto::toEntity)
                .toList();
        pgDataRepository.saveAll(entities);
    }
}
