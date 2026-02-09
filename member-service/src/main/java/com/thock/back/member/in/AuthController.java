package com.thock.back.member.in;


import com.thock.back.global.exception.ErrorResponse;
import com.thock.back.member.app.AuthApplicationService;
import com.thock.back.member.domain.command.LoginCommand;
import com.thock.back.member.in.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
@Tag(name = "auth-controller", description = "인증 관련 API")
public class AuthController {

    private final AuthApplicationService authApplicationService;

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {

        AuthenticationResult result = authApplicationService.login(new LoginCommand(request.email(), request.password()));

        return ResponseEntity.ok(new LoginResponse(result.accessToken(), result.refreshToken()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenRefreshResponse> refresh(@RequestBody TokenRefreshRequest request) {
        AuthenticationResult result = authApplicationService.refreshAccessToken(request.refreshToken());
        return ResponseEntity.ok(new TokenRefreshResponse(result.accessToken(), result.refreshToken()));
    }
}

