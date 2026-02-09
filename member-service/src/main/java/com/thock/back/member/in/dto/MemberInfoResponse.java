package com.thock.back.member.in.dto;


import com.thock.back.shared.member.domain.MemberRole;
import io.swagger.v3.oas.annotations.media.Schema;

public record MemberInfoResponse (
        Long memberId,
        MemberRole role
) {}
