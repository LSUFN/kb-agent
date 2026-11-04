package com.example.kbagent.user.service;

import java.util.Set;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.example.kbagent.user.domain.Region;
import com.example.kbagent.user.domain.Role;

/**
 * 创建用户。用户名不区分大小写，保存为小写；区域范围只对 ANALYST 有效（见 SysUser 的不变式）。
 */
public record CreateUserCommand(
        @NotBlank
        @Pattern(regexp = "^[A-Za-z0-9_.-]{3,32}$", message = "用户名为 3-32 位字母、数字、下划线、点或连字符")
        String username,

        @NotBlank
        @Size(min = 8, max = 64, message = "密码长度为 8-64 位")
        String password,

        @NotBlank
        @Size(max = 64)
        String displayName,

        @NotNull
        Role role,

        Set<Region> regionScope) {
}
