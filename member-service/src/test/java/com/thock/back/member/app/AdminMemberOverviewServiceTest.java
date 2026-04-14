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
import com.thock.back.shared.member.domain.MemberState;
import com.thock.back.shared.payment.dto.WalletDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminMemberOverviewServiceTest {

    @Mock
    private MemberRepository memberRepository;
    @Mock
    private MarketOrderClient marketOrderClient;
    @Mock
    private PaymentWalletClient paymentWalletClient;

    private AdminMemberOverviewService adminMemberOverviewService;

    @BeforeEach
    void setUp() {
        TaskExecutor directExecutor = Runnable::run;
        adminMemberOverviewService = new AdminMemberOverviewService(
                memberRepository,
                marketOrderClient,
                paymentWalletClient,
                directExecutor
        );
    }

    @Test
    void getOverview_returnsComposedResponseForAdmin() {
        AuthenticatedUser admin = AuthenticatedUser.of(99L, MemberRole.ADMIN, MemberState.ACTIVE);
        Member member = activeMember(1L);

        WalletDto wallet = new WalletDto(
                10L,
                1L,
                "tester",
                50000L,
                12000L,
                LocalDateTime.now().minusDays(5),
                LocalDateTime.now()
        );

        MarketOrderSummaryDto order = new MarketOrderSummaryDto(
                101L,
                "ORDER-123",
                "PAYMENT_COMPLETED",
                240000L,
                LocalDateTime.now().minusDays(1)
        );

        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
        when(paymentWalletClient.getWallet(1L)).thenReturn(wallet);
        when(marketOrderClient.getRecentOrderSummaries(1L, 5)).thenReturn(List.of(order));

        AdminMemberOverviewResponse response =
                adminMemberOverviewService.getOverview(admin, 1L, 5);

        assertThat(response.memberId()).isEqualTo(1L);
        assertThat(response.email()).isEqualTo("user@test.com");
        assertThat(response.wallet().balance()).isEqualTo(50000L);
        assertThat(response.recentOrders()).hasSize(1);
        assertThat(response.recentOrders().get(0).orderNumber()).isEqualTo("ORDER-123");
    }

    @Test
    void getOverview_whenUserIsNotAdmin_throwsForbidden() {
        AuthenticatedUser user = AuthenticatedUser.of(99L, MemberRole.USER, MemberState.ACTIVE);

        assertThatThrownBy(() -> adminMemberOverviewService.getOverview(user, 1L, 5))
                .isInstanceOf(CustomException.class)
                .extracting(ex -> ((CustomException) ex).getErrorCode())
                .isEqualTo(ErrorCode.ADMIN_FORBIDDEN);

        verifyNoInteractions(memberRepository, paymentWalletClient, marketOrderClient);
    }

    @Test
    void getOverview_whenWalletClientFails_throwsServiceUnavailable() {
        AuthenticatedUser admin = AuthenticatedUser.of(99L, MemberRole.ADMIN, MemberState.ACTIVE);
        Member member = activeMember(1L);
        MarketOrderSummaryDto order = new MarketOrderSummaryDto(
                101L,
                "ORDER-123",
                "PAYMENT_COMPLETED",
                240000L,
                LocalDateTime.now().minusDays(1)
        );

        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
        when(paymentWalletClient.getWallet(1L)).thenThrow(new RuntimeException("payment timeout"));
        when(marketOrderClient.getRecentOrderSummaries(1L, 5)).thenReturn(List.of(order));

        assertThatThrownBy(() -> adminMemberOverviewService.getOverview(admin, 1L, 5))
                .isInstanceOf(CustomException.class)
                .extracting(ex -> ((CustomException) ex).getErrorCode())
                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    void getOverview_whenMarketClientFails_throwsServiceUnavailable() {
        AuthenticatedUser admin = AuthenticatedUser.of(99L, MemberRole.ADMIN, MemberState.ACTIVE);
        Member member = activeMember(1L);

        WalletDto wallet = new WalletDto(
                10L,
                1L,
                "tester",
                50000L,
                12000L,
                LocalDateTime.now().minusDays(5),
                LocalDateTime.now()
        );

        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
        when(paymentWalletClient.getWallet(1L)).thenReturn(wallet);
        when(marketOrderClient.getRecentOrderSummaries(1L, 5))
                .thenThrow(new RuntimeException("market timeout"));

        assertThatThrownBy(() -> adminMemberOverviewService.getOverview(admin, 1L, 5))
                .isInstanceOf(CustomException.class)
                .extracting(ex -> ((CustomException) ex).getErrorCode())
                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
    }

    private Member activeMember(Long id) {
        Member member = Member.signUp("user@test.com", "tester");
        ReflectionTestUtils.setField(member, "id", id);
        return member;
    }
}
