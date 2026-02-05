package com.thock.back.settlement.shared.reconciliation.dto;


public record SettlementTargetDto (
        Long snapshotId,
        Long sellerId,
        Long amount){
}
