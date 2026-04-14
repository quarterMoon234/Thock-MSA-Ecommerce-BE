package com.thock.back.member.out.client;

import com.thock.back.member.config.MemberFeignConfig;
import com.thock.back.member.out.client.dto.MarketOrderSummaryDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(
        name = "member-market-order-client",
        url = "${custom.global.marketServiceUrl}/api/v1/orders/internal",
        configuration = MemberFeignConfig.class
)
public interface MarketOrderClient {

    @GetMapping("/members/{memberId}/summaries")
    List<MarketOrderSummaryDto> getRecentOrderSummaries(
            @PathVariable Long memberId,
            @RequestParam int limit
    );
}
