package com.thock.back.member.in.dto;

import com.thock.back.member.domain.entity.Member;
import com.thock.back.shared.member.domain.MemberRole;
import com.thock.back.shared.member.domain.MemberState;
import com.thock.back.shared.payment.dto.WalletDto;

import java.time.LocalDateTime;
import java.util.List;

public record AdminMemberOverviewResponse(
        Long memberId,
        String email,
        String name,
        MemberRole role,
        MemberState state,
        LocalDateTime lastLoginAt,
        WalletSummary wallet,
        List<OrderSummary> recentOrders
) {
    public static AdminMemberOverviewResponse of(
            Member member,
            WalletDto wallet,
            List<OrderSummary> recentOrders
    ) {
        return new AdminMemberOverviewResponse(
                member.getId(),
                member.getEmail(),
                member.getName(),
                member.getRole(),
                member.getState(),
                member.getLastLoginAt(),
                WalletSummary.from(wallet),
                recentOrders
        );
    }

    public record WalletSummary(
            Long balance,
            Long revenue,
            LocalDateTime updatedAt
    ) {
        public static WalletSummary from(WalletDto walletDto) {
            return new WalletSummary(
                    walletDto.balance(),
                    walletDto.revenue(),
                    walletDto.updatedAt()
            );
        }
    }

    public record OrderSummary(
            Long orderId,
            String orderNumber,
            String state,
            Long totalSalePrice,
            LocalDateTime createdAt
    ) {
    }
}
