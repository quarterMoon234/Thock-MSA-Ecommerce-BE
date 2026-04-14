package com.thock.back.member.app;

import com.thock.back.global.exception.CustomException;
import com.thock.back.global.exception.ErrorCode;
import com.thock.back.global.security.AuthenticatedUser;
import com.thock.back.member.domain.entity.Member;
import com.thock.back.member.in.dto.AdminMemberOverviewResponse;
import com.thock.back.member.out.MemberRepository;
import com.thock.back.member.out.client.MarketOrderClient;
import com.thock.back.member.out.client.PaymentWalletClient;
import com.thock.back.member.out.client.dto.MarketOrderSummaryDto;
import com.thock.back.shared.member.domain.MemberRole;
import com.thock.back.shared.payment.dto.WalletDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminMemberOverviewService {

    private final MemberRepository memberRepository;
    private final MarketOrderClient marketOrderClient;
    private final PaymentWalletClient paymentWalletClient;
    private final TaskExecutor taskExecutor;

    @Transactional(readOnly = true)
    public AdminMemberOverviewResponse getOverview(
            AuthenticatedUser user,
            Long memberId,
            int orderLimit
    ) {
        validateAdmin(user);

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));

        int safeOrderLimit = sanitizeLimit(orderLimit);

        CompletableFuture<WalletDto> walletFuture =
                CompletableFuture.supplyAsync(() -> fetchWallet(memberId), taskExecutor);
        CompletableFuture<List<MarketOrderSummaryDto>> ordersFuture =
                CompletableFuture.supplyAsync(() -> fetchRecentOrders(memberId, safeOrderLimit), taskExecutor);

        WalletDto wallet = joinFuture(walletFuture);
        List<AdminMemberOverviewResponse.OrderSummary> recentOrders =
                joinFuture(ordersFuture)
                        .stream()
                        .map(this::toOrderSummary)
                        .toList();

        return AdminMemberOverviewResponse.of(member, wallet, recentOrders);
    }

    private void validateAdmin(AuthenticatedUser user) {
        if (user.role() != MemberRole.ADMIN) {
            throw new CustomException(ErrorCode.ADMIN_FORBIDDEN);
        }
    }

    private int sanitizeLimit(int orderLimit) {
        return Math.min(Math.max(orderLimit, 1), 10);
    }

    private WalletDto fetchWallet(Long memberId) {
        try {
            return paymentWalletClient.getWallet(memberId);
        } catch (Exception e) {
            log.warn("[ADMIN] Failed to fetch wallet from payment-service. memberId={}", memberId, e);
            throw new CustomException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private List<MarketOrderSummaryDto> fetchRecentOrders(Long memberId, int orderLimit) {
        try {
            return marketOrderClient.getRecentOrderSummaries(memberId, orderLimit);
        } catch (Exception e) {
            log.warn("[ADMIN] Failed to fetch orders from market-service. memberId={}, orderLimit={}",
                    memberId, orderLimit, e);
            throw new CustomException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private <T> T joinFuture(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof CustomException customException) {
                throw customException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw e;
        }
    }

    private AdminMemberOverviewResponse.OrderSummary toOrderSummary(MarketOrderSummaryDto order) {
        return new AdminMemberOverviewResponse.OrderSummary(
                order.orderId(),
                order.orderNumber(),
                order.state(),
                order.totalSalePrice(),
                order.createdAt()
        );
    }
}
