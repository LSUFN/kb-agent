package com.example.kbagent.user.service;

import java.util.Set;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.example.kbagent.user.domain.Region;
import com.example.kbagent.user.domain.Role;

/**
 * 部分更新用户（PATCH）：字段为 null 表示不修改。清空区域范围请传空数组。
 */
public record UpdateUserCommand(
        @Size(max = 64)
        @Pattern(regexp = ".*\\S.*", message = "显示名不能为空白")
        String displayName,

        Role role,

        Set<Region> regionScope,

        Boolean enabled) {
}
