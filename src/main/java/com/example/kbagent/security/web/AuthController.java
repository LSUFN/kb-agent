package com.example.kbagent.security.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.example.kbagent.common.api.ApiResponse;
import com.example.kbagent.security.ActorAuthentication;
import com.example.kbagent.security.AuthService;
import com.example.kbagent.user.service.CurrentUserView;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    public record LoginRequest(@NotBlank @Size(max = 64) String username,
                               @NotBlank @Size(max = 128) String password) {
    }

    public record RefreshRequest(@NotBlank @Size(max = 128) String refreshToken) {
    }

    public record LogoutRequest(@Size(max = 128) String refreshToken) {
    }

    /** expiresIn / refreshExpiresIn 的单位是秒。 */
    public record TokenResponse(String tokenType, String accessToken, long expiresIn,
                                String refreshToken, long refreshExpiresIn, CurrentUserView user) {

        static TokenResponse of(AuthService.IssuedTokens tokens) {
            return new TokenResponse("Bearer", tokens.accessToken().value(), tokens.accessTokenTtl().toSeconds(),
                    tokens.refreshToken(), tokens.refreshTokenTtl().toSeconds(), CurrentUserView.of(tokens.actor()));
        }
    }

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResponse<TokenResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return ApiResponse.ok(TokenResponse.of(
                authService.login(request.username(), request.password(), http.getRemoteAddr())));
    }

    @PostMapping("/refresh")
    public ApiResponse<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ApiResponse.ok(TokenResponse.of(authService.refresh(request.refreshToken())));
    }

    /** 需要登录。请求体可选：带上 refreshToken 时一并撤销。 */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(ActorAuthentication authentication,
                                    @Valid @RequestBody(required = false) LogoutRequest request) {
        authService.logout(authentication, request == null ? null : request.refreshToken());
        return ApiResponse.ok();
    }
}
