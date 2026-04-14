package com.thock.back.member.out.client;

import com.thock.back.member.config.MemberFeignConfig;
import com.thock.back.shared.payment.dto.WalletDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(
        name = "member-payment-wallet-client",
        url = "${custom.global.paymentServiceUrl}/api/v1/payments/internal",
        configuration = MemberFeignConfig.class
)
public interface PaymentWalletClient {

    @GetMapping("/wallets/{memberId}")
    WalletDto getWallet(@PathVariable Long memberId);
}
