package com.example.kbagent.user.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import com.example.kbagent.common.api.ApiResponse;
import com.example.kbagent.common.api.PageResponse;
import com.example.kbagent.user.domain.Actor;
import com.example.kbagent.user.service.AdminUserService;
import com.example.kbagent.user.service.CreateUserCommand;
import com.example.kbagent.user.service.UpdateUserCommand;
import com.example.kbagent.user.service.UserView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户管理（ADMIN）。安全配置已经按 URL 限制了 /api/v1/admin/**，这里再加方法级注解作为第二道防线。
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final AdminUserService adminUserService;

    public AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<UserView>> create(@Valid @RequestBody CreateUserCommand command) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(adminUserService.create(command)));
    }

    @GetMapping
    public ApiResponse<PageResponse<UserView>> list(
            @RequestParam(required = false) @Size(max = 64) String keyword,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(adminUserService.list(keyword, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<UserView> get(@PathVariable long id) {
        return ApiResponse.ok(adminUserService.get(id));
    }

    @PatchMapping("/{id}")
    public ApiResponse<UserView> update(@PathVariable long id, @Valid @RequestBody UpdateUserCommand command,
                                        @AuthenticationPrincipal Actor operator) {
        return ApiResponse.ok(adminUserService.update(id, command, operator));
    }
}
