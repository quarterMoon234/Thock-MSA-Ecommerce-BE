package com.thock.back.member.in;

import com.thock.back.global.security.AuthUser;
import com.thock.back.global.security.AuthenticatedUser;
import com.thock.back.member.app.AdminMemberOverviewService;
import com.thock.back.member.in.dto.AdminMemberOverviewResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/members")
@RequiredArgsConstructor
@Tag(name = "admin-member-controller", description = "관리자 회원 통합 조회 API")
public class AdminMemberController {

    private final AdminMemberOverviewService adminMemberOverviewService;

    @Operation(summary = "회원 통합 조회", description = "관리자가 회원, 최근 주문, 지갑 정보를 통합 조회합니다. ")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "403", description = "관리자 권한 없음"),
            @ApiResponse(responseCode = "404", description = "회원 없음")
    })
    @GetMapping("/{memberId}/overview")
    public ResponseEntity<AdminMemberOverviewResponse> getOverview(
            @AuthUser AuthenticatedUser user,
            @PathVariable Long memberId,
            @RequestParam(defaultValue = "5") int orderLimit
    ) {
        return ResponseEntity.ok(
                adminMemberOverviewService.getOverview(user, memberId, orderLimit)
        );
    }
}
