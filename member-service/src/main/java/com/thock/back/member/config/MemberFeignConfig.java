package com.thock.back.member.config;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MemberFeignConfig {

    @Value("${SECURITY_SERVICE_INTERNAL_SECRET:${SECURITY_GATEWAY_INTERNAL_SECRET:}}")
    private String internalSecret;

    @Bean
    public RequestInterceptor internalAuthRequestInterceptor() {
        return template -> {
            if (internalSecret != null && !internalSecret.isBlank()) {
                template.header("X-Internal-Auth", internalSecret);
            }
        };
    }
}
